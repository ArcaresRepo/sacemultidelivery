package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.ContainedEntities;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.workflow.WorkflowManager;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

public class SaveStarter implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;
    private final String updateDBArcaresRuleName = "UpdateDBArcares"; 

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
            WorkflowManager wfManager = workSession.getWorkflowManager();
            logger.info("SaveStarter - run : Inizio");
            
            //Signal
            StructuredProperty wfStr = entity.getStructuredProperty("workflow");
            List strRecords = wfStr.getRecords();
            for (int k = 0; k < strRecords.size(); k++) {
                StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(k);
                String wrkName = "" + curRecord.getProperty("workflowName").getValue();
                String instanceId = "" + curRecord.getProperty("instanceId").getValue();
                String taskId = "" + curRecord.getProperty("taskId").getValue();
                String state = "" + curRecord.getProperty("state").getValue();
                if(state.equals("Wait")) {
                    logger.info("SaveStarter -  run: Signal ... " + wrkName + " instanceId: " + instanceId + " taskId: " + taskId + " state: " + state);
                    try {
                        entity.signal(instanceId, taskId);
                        workSession.save();
                    } catch (Exception ex) {
                        logger.error("SaveStarter -  run: Cannot signal entity " + entity.getId() + ": " + ex.getMessage(), ex);
                    }

                    ContainedEntities headers = entity.getContainedEntities("header");            
                    for (Object head : headers) {
                        Entity header = (Entity) head;
                        // Salvo il singolo documento utilizzando la regola SaveDocumento
                        StructuredProperty headStr = header.getStructuredProperty("workflow");
                        List strHeadRecords = headStr.getRecords();
                        for (int i = 0; i < strHeadRecords.size(); i++) {
                            curRecord = (StructuredPropertyRecord) strHeadRecords.get(i);
                            wrkName = "" + curRecord.getProperty("workflowName").getValue();
                            instanceId = "" + curRecord.getProperty("instanceId").getValue();
                            taskId = "" + curRecord.getProperty("taskId").getValue();
                            state = "" + curRecord.getProperty("state").getValue();

                            logger.info("SaveStarter - Signal to " + wrkName + "  with instanceId: " + instanceId + ",taskId: " + taskId + " and state: " + state);
                            try {
                                wfManager.signalMessage(instanceId, "invia");
                                workSession.save();
                            } catch (Exception ex) {
                                logger.error("SaveStarter - Cannot send signal to entity " + header.getId() + ": " + ex.getMessage(), ex);
                            }
                        }

                        ContainedEntities documenti = header.getContainedEntities("documento");
                        for (Object doc : documenti) {
                            Entity documento = (Entity) doc;
                            // Salvo il singolo documento utilizzando la regola SaveDocumento
                            StructuredProperty documentoStr = documento.getStructuredProperty("workflow");
                            List strDocRecords = documentoStr.getRecords();
                            for (int i = 0; i < strDocRecords.size(); i++) {
                                curRecord = (StructuredPropertyRecord) strDocRecords.get(i);
                                wrkName = "" + curRecord.getProperty("workflowName").getValue();
                                instanceId = "" + curRecord.getProperty("instanceId").getValue();
                                taskId = "" + curRecord.getProperty("taskId").getValue();
                                state = "" + curRecord.getProperty("state").getValue();

                                logger.info("SaveStarter - Signal to " + wrkName + "  with instanceId: " + instanceId + ",taskId: " + taskId + " and state: " + state);
                                try {
                                    wfManager.signalMessage(instanceId, "inviaDocumento");
                                    workSession.save();
                                } catch (Exception ex) {
                                    logger.error("SaveStarter - Cannot send signal to entity " + documento.getId() + ": " + ex.getMessage(), ex);
                                }
                            }
                        }
                    }

                    for (Object head : headers) {
                        Entity header = (Entity) head;       
                        String idFlusso = (String) header.getPropertyValue("idFlusso");

                        String update = "UPDATE WRDC_HEADER SET COD_STATO = '2' WHERE ID_FLUSSO = '" + idFlusso + "'";
                        HashMap paramsUpdate = new HashMap();
                        paramsUpdate.put("workSession", workSession);
                        paramsUpdate.put("logger", logger);
                        paramsUpdate.put("query", update);
                        logger.info("calling externalRule " + updateDBArcaresRuleName);
                        WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);
                        
                        Entity newEntity = workSession.getEntityById(header.getId());
                        newEntity.setProperty("stato", "2");
                        newEntity.persist();
                        workSession.save();
                    }
                }
                
            }             
            logger.info("SaveStarter -  run: Save performed");
        } catch (Exception ex) {
            logger.error("SaveStarter -  run: Errore inatteso:" + ex + "\n StackTrace \n" + ExceptionUtils.getStackTrace(ex));
            throw new RuntimeException(ex);
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.info("SaveStarter - run : Fine");
        }
        return entity.getId();
    }

}
