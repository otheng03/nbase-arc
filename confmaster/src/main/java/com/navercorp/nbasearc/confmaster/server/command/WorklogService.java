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

import java.io.IOException;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.navercorp.nbasearc.confmaster.ConfMaster;
import com.navercorp.nbasearc.confmaster.Constant;
import com.navercorp.nbasearc.confmaster.ConfMasterException.MgmtZooKeeperException;
import com.navercorp.nbasearc.confmaster.server.lock.HierarchicalLockHelper;
import com.navercorp.nbasearc.confmaster.server.mapping.CommandMapping;
import com.navercorp.nbasearc.confmaster.server.mapping.LockMapping;
import com.navercorp.nbasearc.confmaster.server.workflow.WorkflowLogger;

@Service
public class WorklogService {

    @Autowired
    private WorkflowLogger workflowLogger;

    @CommandMapping(name="worklog_info", 
            usage="worklog_info",
            requiredState=ConfMaster.READY)
    public String worklogInfo() throws JsonParseException, JsonMappingException,
            KeeperException, InterruptedException, IOException {
        if (workflowLogger.getNumLogs() == 0) {
            return "-ERR confmaster does not have any log.";
        }
        
        final long logStx = workflowLogger.getNumOfStartLog();
        final long logEdx = logStx + workflowLogger.getNumLogs() - 1;
        
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"start\":");
        sb.append(logStx);
        sb.append(",\"end\":");
        sb.append(logEdx);
        sb.append("}");
        
        return sb.toString();
    }
    
    @LockMapping(name="worklog_info")
    public void worklogInfoLock(HierarchicalLockHelper lockHelper) {
        // Do nothing...
    }
    
    @CommandMapping(name="worklog_get", 
            usage="worklog_get <start log number> <end log number>\r\n" +
                    "get workflow logs",
            requiredState=ConfMaster.READY)
    public String worklogGet(Long requestedLogStartNo, Long requestedLogEndNo)
            throws MgmtZooKeeperException {
        String err = workflowLogger.isValidLogNo(requestedLogStartNo, requestedLogEndNo);
        if (null != err) {
            return err;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"list\":[");
        
        long deletedNo = -1;
        long no = requestedLogStartNo;
        for (; no <= requestedLogEndNo ; no ++) {
        	WorkflowLogger.ZkWorkflowLog log = workflowLogger.getLog(no);
            if (null == log) {
                deletedNo = no;
                break;
            }
            
            sb.append(log.toJsonString());
            sb.append(", ");
        }

        if (sb.length() > "{\"list\":[".length()) {
            sb.delete(sb.length() - 2, sb.length());
        }
        
        if (-1 != deletedNo) {
            sb.append("],\"msg\":\"workflog log " + deletedNo
                    + " is already deleted.\"}");
        } else {
            sb.append("]}");
        }

        return sb.toString();
    }

    @LockMapping(name="worklog_get")
    public void worklogGetLock(HierarchicalLockHelper lockHelper) {
        // Do nothing...
    }
    
    @CommandMapping(name="worklog_head",
            usage="worklog_head <the number of logs>\r\n" +
                    "get and delete workflow logs from beginning",
            requiredState=ConfMaster.READY)
    public String worklogHead(Long logCount) throws NoNodeException,
            MgmtZooKeeperException {
        if (workflowLogger.getNumLogs() == 0) {
            return "-ERR confmaster does not have any log.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"list\":[");
        
        long offset = 0;
        for (; offset < logCount; offset++) {
        	WorkflowLogger.ZkWorkflowLog log = workflowLogger.getLogFromBeginning(offset);
            if (null == log) {
                break;
            }
            sb.append(log.toJsonString());
            sb.append(", ");
        }
        
        if (offset > 0) {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append("]}");

        return sb.toString();
    }

    @LockMapping(name="worklog_head")
    public void worklogHeadLock(HierarchicalLockHelper lockHelper) {
        // Do nothing...
    }
    
    @CommandMapping(name="worklog_del",
            usage="worklog_del <max log nubmer>\r\n" +
                    "delete workflow logs",
            requiredState=ConfMaster.RUNNING)
    public String worklogDel(Long maxLogNo) {
        if (workflowLogger.getNumLogs() == 0) {
            return "-ERR confmaster does not have any log.";
        }
        
        while (true) {
            boolean ret = workflowLogger.deleteLog(maxLogNo);
            if (!ret) {
                break;
            }
        }
        
        return Constant.S2C_OK;
    }

    @LockMapping(name="worklog_del")
    public void worklogDelLock(HierarchicalLockHelper lockHelper) {
        // Do nothing...
    }
    
}
