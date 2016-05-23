/*
 * Copyright 2015 Naver Corp.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.nbasearc.confmaster.server.command;

import static com.navercorp.nbasearc.confmaster.Constant.*;
import static com.navercorp.nbasearc.confmaster.Constant.Color.GREEN;
import static com.navercorp.nbasearc.confmaster.repository.lock.LockType.READ;
import static com.navercorp.nbasearc.confmaster.repository.lock.LockType.WRITE;

import java.util.List;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.navercorp.nbasearc.confmaster.ConfMasterException.MgmtCommandWrongArgumentException;
import com.navercorp.nbasearc.confmaster.ConfMasterException.MgmtZooKeeperException;
import com.navercorp.nbasearc.confmaster.config.Config;
import com.navercorp.nbasearc.confmaster.io.MultipleGatewayInvocator;
import com.navercorp.nbasearc.confmaster.logger.Logger;
import com.navercorp.nbasearc.confmaster.repository.PathUtil;
import com.navercorp.nbasearc.confmaster.repository.dao.PartitionGroupDao;
import com.navercorp.nbasearc.confmaster.repository.dao.zookeeper.ZkNotificationDao;
import com.navercorp.nbasearc.confmaster.repository.dao.zookeeper.ZkWorkflowLogDao;
import com.navercorp.nbasearc.confmaster.repository.lock.HierarchicalLockHelper;
import com.navercorp.nbasearc.confmaster.repository.lock.HierarchicalLockPGSList;
import com.navercorp.nbasearc.confmaster.repository.znode.PartitionGroupData;
import com.navercorp.nbasearc.confmaster.server.ThreadPool;
import com.navercorp.nbasearc.confmaster.server.cluster.Cluster;
import com.navercorp.nbasearc.confmaster.server.cluster.Gateway;
import com.navercorp.nbasearc.confmaster.server.cluster.PartitionGroup;
import com.navercorp.nbasearc.confmaster.server.cluster.PartitionGroupServer;
import com.navercorp.nbasearc.confmaster.server.cluster.RedisServer;
import com.navercorp.nbasearc.confmaster.server.imo.ClusterImo;
import com.navercorp.nbasearc.confmaster.server.imo.GatewayImo;
import com.navercorp.nbasearc.confmaster.server.imo.PartitionGroupImo;
import com.navercorp.nbasearc.confmaster.server.imo.PartitionGroupServerImo;
import com.navercorp.nbasearc.confmaster.server.imo.RedisServerImo;
import com.navercorp.nbasearc.confmaster.server.mapping.CommandMapping;
import com.navercorp.nbasearc.confmaster.server.mapping.LockMapping;
import com.navercorp.nbasearc.confmaster.server.workflow.BlueJoinWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.DecreaseQuorumWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.IncreaseQuorumWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.MasterElectionWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.MembershipGrantWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.QuorumAdjustmentWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.RoleAdjustmentWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.RoleChangeWorkflow;
import com.navercorp.nbasearc.confmaster.server.workflow.WorkflowExecutor;
import com.navercorp.nbasearc.confmaster.server.workflow.YellowJoinWorkflow;

@Service
public class PartitionGroupService {

    @Autowired
    private Config config;
    @Autowired
    private ThreadPool executor;
    @Autowired
    private ApplicationContext context;

    @Autowired
    private ClusterImo clusterImo;
    @Autowired
    private PartitionGroupImo pgImo;
    @Autowired
    private PartitionGroupServerImo pgsImo;
    @Autowired
    private RedisServerImo rsImo;
    @Autowired
    private GatewayImo gwImo;

    @Autowired
    private PartitionGroupDao pgDao;
    @Autowired
    private ZkNotificationDao notificationDao;
    @Autowired
    private ZkWorkflowLogDao workflowLogDao;
    
    @Autowired
    private WorkflowExecutor wfExecutor;
    
    @CommandMapping(name="pg_add",
            usage="pg_add <cluster_name> <pgid>\r\n" +
                    "add a single partition group")
    public String pgAdd(String clusterName, String pgId)
            throws MgmtCommandWrongArgumentException, NodeExistsException,
            MgmtZooKeeperException, NoNodeException {
        // Check
        if (null != pgImo.get(pgId, clusterName)) {
            return "-ERR duplicated pgid";
        }
        
        // In Memory
        Cluster cluster = clusterImo.get(clusterName);
        List<Gateway> gwList = gwImo.getList(clusterName);

        // Prepare        
        String reply = cluster.isGatewaysAlive(gwImo);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }

        MultipleGatewayInvocator broadcast = new MultipleGatewayInvocator(); 
        reply = broadcast.request(clusterName, gwList, GW_PING, GW_PONG, executor);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }

        PartitionGroupData data = 
            PartitionGroupData.builder().from(new PartitionGroupData()).build();
        
        // DB
        pgDao.createPg(pgId, clusterName, data);
        
        // In Memory
        PartitionGroup pg = pgImo.load(pgId, clusterName);

        String cmd = String.format("pg_add %s", pgId);
        reply = broadcast.request(clusterName, gwList, cmd, GW_RESPONSE_OK, executor);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }

        // Log
        workflowLogDao.log(0, SEVERITY_MODERATE, 
                "PGAddCommand", LOG_TYPE_COMMAND, 
                clusterName, "Add pg success. " + pg, 
                String.format("{\"cluster_name\":\"%s\",\"pg_id\":\"%s\"}", 
                        clusterName, pgId));
        
        return S2C_OK;
    }

    @LockMapping(name="pg_add")
    public void pgAddLock(HierarchicalLockHelper lockHelper, String clusterName) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(WRITE).pgsList(READ)
                .gwList(READ).gw(WRITE, ALL);
    }

    @CommandMapping(name="pg_del",
            usage="pg_del <cluster_name> <pgid>\r\n" +
                    "delete a single partition group")
    public String pgDel(String clusterName, String pgId)
            throws MgmtZooKeeperException {
        // Check
        PartitionGroup pg = pgImo.get(pgId, clusterName); 
        if (null == pg) {
            throw new IllegalArgumentException(
                    EXCEPTIONMSG_PARTITION_GROUP_SERVER_DOES_NOT_EXIST
                            + PartitionGroup.fullName(clusterName, pgId));
        }

        if (!pg.getData().getPgsIdList().isEmpty()) {
            throw new IllegalArgumentException(EXCEPTIONMSG_PG_NOT_EMPTY
                    + PartitionGroup.fullName(clusterName, pgId));
        }

        // In Memory
        Cluster cluster = clusterImo.get(clusterName);
        List<Gateway> gwList = gwImo.getList(clusterName);
        
        String reply = cluster.isGatewaysAlive(gwImo);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }

        // Prepare
        MultipleGatewayInvocator broadcast = new MultipleGatewayInvocator(); 
        reply = broadcast.request(clusterName, gwList, GW_PING, GW_PONG, executor);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }
        
        // DB
        pgDao.deletePg(pgId, clusterName);
        
        // In Memory
        pgImo.delete(PathUtil.pgPath(pgId, clusterName));

        String cmd = String.format("pg_del %s", pgId);
        reply = broadcast.request(clusterName, gwList, cmd, GW_RESPONSE_OK, executor);
        if (null != reply) {
            Logger.error(reply);
            return reply;
        }
        
        // Logging
        workflowLogDao.log(0, SEVERITY_MODERATE, 
                "PGDelCommand", LOG_TYPE_COMMAND, 
                clusterName, "Delte pg success. " + pg, 
                String.format("{\"cluster_name\":\"%s\",\"pg_id\":\"%s\"}", 
                        clusterName, pgId));
        
        return S2C_OK;
    }

    @LockMapping(name="pg_del")
    public void pgDelLock(HierarchicalLockHelper lockHelper, String clusterName, String pgId) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(WRITE).pgsList(READ).pg(WRITE, pgId)
                .gwList(READ).gw(WRITE, ALL);
    }

    @CommandMapping(name="pg_info",
            usage="pg_info <cluster_name> <pg_id>\r\n" +
                    "get information of a Partition Group")
    public String pgInfo(String clusterName, String pgid) {
        // Check
        if (null == clusterImo.get(clusterName)) {
            return EXCEPTIONMSG_CLUSTER_DOES_NOT_EXIST;
        }
        
        // In Memory
        PartitionGroup pg = pgImo.get(pgid, clusterName);
        if (null == pg) {
            return "-ERR pgs does not exist.";
        }

        // Do
        try {
            return pg.getData().toString();
        } catch (RuntimeException e) {
            return "-ERR internal data of pgs is not correct.";
        }
    }

    @LockMapping(name="pg_info")
    public void pgInfoLock(HierarchicalLockHelper lockHelper, String clusterName, String pgid) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(READ).pgsList(READ).pg(READ, pgid);
    }

    @CommandMapping(name="pg_ls",
            usage="pg_ls <cluster_name>")
    public String pgLs(String clusterName) throws KeeperException,
            InterruptedException {
        // In Memory
        List<PartitionGroup> pgList = pgImo.getList(clusterName);
        Cluster cluster = clusterImo.get(clusterName);
        if (null == cluster) {
            return EXCEPTIONMSG_CLUSTER_DOES_NOT_EXIST;
        }

        // Do
        StringBuilder reply = new StringBuilder();
        reply.append("{\"list\":[");
        for (PartitionGroup pg : pgList) {
            reply.append("\"").append(pg.getName()).append("\", ");
        }
        if (!pgList.isEmpty()) {
            reply.delete(reply.length()-2,reply.length()).append("]}");
        } else {
            reply.append("]}");
        }
        return reply.toString();
    }

    @LockMapping(name="pg_ls")
    public void pgLsLock(HierarchicalLockHelper lockHelper, String clusterName) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(READ).pgsList(READ)
                .pg(READ, ALL);
    }

    @CommandMapping(name="role_change",
            usage="role_change <cluster_name> <pgs_id>")
    public String roleChange(String clusterName, String pgsid)
            throws KeeperException, InterruptedException {
        Cluster cluster = clusterImo.get(clusterName);
        if (cluster == null) {
            throw new IllegalArgumentException(
                    EXCEPTIONMSG_CLUSTER_DOES_NOT_EXIST
                            + Cluster.fullName(clusterName));
        }

        PartitionGroupServer masterCandidate = pgsImo.get(pgsid, clusterName);
        if (masterCandidate == null) {
            throw new IllegalArgumentException(
                    EXCEPTIONMSG_PARTITION_GROUP_SERVER_DOES_NOT_EXIST
                            + PartitionGroupServer.fullName(clusterName, pgsid));
        }
        
        PartitionGroup pg = pgImo.get(String.valueOf(masterCandidate.getData().getPgId()), clusterName);
        if (pg == null) {
            throw new IllegalArgumentException(
                    EXCEPTIONMSG_PARTITION_GROUP_DOES_NOT_EXIST
                            + PartitionGroup.fullName(clusterName,
                                    String.valueOf(masterCandidate.getData().getPgId())));
        }
        
        List<PartitionGroupServer> joinedPgsList = 
                pg.getJoinedPgsList(pgsImo.getList(clusterName, Integer.valueOf(pg.getName())));
        
        if (pg.getSlaves(joinedPgsList).size() == 0) {
            return "-ERR pg doesn't have any slave. " + pg;
        }

        if (pg.getData().getQuorum() - pg.getD(joinedPgsList) <= 0) {
            return "-ERR not enough available pgs. PG.Q: "
                    + pg.getData().getQuorum() + ", D: "
                    + pg.getD(joinedPgsList);
        }
        
        if (!masterCandidate.getData().getRole().equals(PGS_ROLE_SLAVE) 
                || masterCandidate.getData().getColor() != GREEN) {
            return "-ERR the candidate is not a slave and green.";
        }

        // TODO : Check redis replication
        for (PartitionGroupServer pgs : joinedPgsList) {
			if (pgs.getData().getColor() != GREEN 
					|| (!pgs.getData().getRole().equals(PGS_ROLE_MASTER)
							&& !pgs.getData().getRole().equals(PGS_ROLE_SLAVE))) {
        		continue;
        	}
        	
            RedisServer r = rsImo.get(pgs.getName(), pgs.getClusterName());
            try {
                if (null == r) {
                    return "-ERR check redis replication ping fail, redis object doesn't exist, " + pgs;
                }
                
                String reply = r.executeQuery(REDIS_REP_PING);
                if (!reply.equals(REDIS_PONG)) {
                    return "-ERR check redis replication ping fail, invalid reply. "
                            + pgs + ", REPLY: " + reply;
                }
            } catch (Exception e) {
                return "-ERR check redis replication ping fail, " + pgs + ", EXCEPTION: " + e.getMessage();
            }
        }
        
        RoleChangeWorkflow rc = new RoleChangeWorkflow(pg, masterCandidate, context);
        try {
            rc.execute();
        } catch (Exception e) {
            if (rc.getResultString() == null) {
                Logger.error("Role change fail. " + pg, e);
                return "-ERR failed to role_change. " + e.getMessage();
            }
        } finally {
            try {
                notificationDao.updateGatewayAffinity(cluster);
            } catch (MgmtZooKeeperException e) {
                String msg = "-ERR failed to update gateway affinity. cluster: " + clusterName;
                Logger.error(msg, e);
                return msg;
            }
        }
        return rc.getResultString();
    }

    @LockMapping(name="role_change")
    public void roleChangeLock(HierarchicalLockHelper lockHelper,
            String clusterName, String pgsid) {
        HierarchicalLockPGSList lock = lockHelper.root(READ)
                .cluster(READ, clusterName).pgList(READ).pgsList(READ);
        PartitionGroupServer pgs = pgsImo.get(pgsid, clusterName);
        lock.pg(WRITE, String.valueOf(pgs.getData().getPgId())).pgs(WRITE,
                ALL_IN_PG).gwList(READ);
    }

    @CommandMapping(name="pg_iq",
            usage="pg_iq <cluster_name> <pg_id>")
    public String pgIq(String clusterName, String pgId) throws Exception {
        // In Memory
        PartitionGroup pg = pgImo.get(pgId, clusterName);
        if (null == pg) {
            return EXCEPTIONMSG_PARTITION_GROUP_DOES_NOT_EXIST;
        }

        // Do
        IncreaseQuorumWorkflow iq = new IncreaseQuorumWorkflow(pg, context);
        String result = iq.execute();
        if (!result.equals(S2C_OK)) {
            return result;
        }
        
        return S2C_OK;
    }

    @LockMapping(name="pg_iq")
    public void pgIqLock(HierarchicalLockHelper lockHelper, String clusterName, String pgId) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(READ)
                .pgsList(READ).pg(WRITE, pgId);
    }

    @CommandMapping(name="pg_dq",
            usage="pg_dq <cluster_name> <pg_id>")
    public String pgDq(String clusterName, String pgId) throws Exception {
        // In Memory
        PartitionGroup pg = pgImo.get(pgId, clusterName);
        if (null == pg) {
            return EXCEPTIONMSG_PARTITION_GROUP_DOES_NOT_EXIST;
        }

        // Do
        DecreaseQuorumWorkflow dq = new DecreaseQuorumWorkflow(pg, context);
        String result = dq.execute();
        if (!result.equals(S2C_OK)) {
            return result;
        }
        
        QuorumAdjustmentWorkflow qa = new QuorumAdjustmentWorkflow(pg, true,
                context);
        qa.execute();
        return S2C_OK;
    }

    @LockMapping(name="pg_dq")
    public void pgDqLock(HierarchicalLockHelper lockHelper, String clusterName, String pgId) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(READ)
                .pgsList(READ).pg(WRITE, pgId);
    }

    @CommandMapping(name = "op_wf", usage = "op_wf <cluster_name> <pg_id> <wf> <cascading> forced\r\n"
            + "wf: RA, QA, ME, YJ, BJ, MG")
    public String opWf(String clusterName, String pgId, String wf, boolean cascading, String mode) throws Exception {
        if (!mode.equals(FORCED)) {
            return EXCEPTIONMSG_NOT_FORCED_MODE;
        }
        
        Cluster cluster = clusterImo.get(clusterName);
        if (cluster == null) {
            return EXCEPTIONMSG_CLUSTER_DOES_NOT_EXIST;
        }
        
        PartitionGroup pg = pgImo.get(pgId, clusterName);
        if (pg == null) {
            return EXCEPTIONMSG_PARTITION_GROUP_DOES_NOT_EXIST;
        }
        
        wf = wf.toUpperCase();
        try {
            if (wf.equals("RA")) {
                RoleAdjustmentWorkflow ra = new RoleAdjustmentWorkflow(pg,
                        cascading, context);
                ra.execute();
                return S2C_OK;
            } else if (wf.equals("QA")) {
                QuorumAdjustmentWorkflow qa = new QuorumAdjustmentWorkflow(pg,
                        cascading, context);
                qa.execute();
                return S2C_OK;
            } else if (wf.equals("ME")) {
                MasterElectionWorkflow me = new MasterElectionWorkflow(pg,
                        null, cascading, context);
                me.execute();
                return S2C_OK;
            } else if (wf.equals("YJ")) {
                YellowJoinWorkflow yj = new YellowJoinWorkflow(pg, cascading,
                        context);
                yj.execute();
                return S2C_OK;
            } else if (wf.equals("BJ")) {
                BlueJoinWorkflow bj = new BlueJoinWorkflow(pg, cascading, context);
                bj.execute();
                return S2C_OK;
            } else if (wf.equals("MG")) {
                MembershipGrantWorkflow mg = new MembershipGrantWorkflow(pg, cascading, context);
                mg.execute();
                return S2C_OK;
            }
        } catch (Exception e) {
            return ERROR + " " + e.getMessage();
        }
        
        return EXCEPTIONMSG_NOT_SUPPORTED_WF;
    }

    @LockMapping(name="op_wf")
    public void opWfLock(HierarchicalLockHelper lockHelper, String clusterName, String pgId) {
        lockHelper.root(READ).cluster(READ, clusterName).pgList(READ)
                .pgsList(READ).pg(WRITE, pgId).pgs(WRITE, ALL_IN_PG)
                .gwList(READ).gw(WRITE, ALL);
    }
    
}
