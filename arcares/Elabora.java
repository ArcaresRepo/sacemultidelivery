package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.exceptions.ModelException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * TODO: Rename class to match the same rule name.
 */
public class Elabora implements WrRuleClassBody {

    WorkSession workSession;	// Required
    Logger logger;          	// Required
    Entity entity;				// Required
    String instanceId;
    String taskId;
    
    private final String statoOk = "2";
    
    // ... any other optional parameter can be passed by action's pre-execution

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        instanceId = (String) parameters.get("instanceId");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
        taskId = (String) parameters.get("taskId");
    }
    
    public Object run(Map parameters) {
        setParameters(parameters);
        
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
           
            entity.persist();
            workSession.save();
            
            boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
            boolean flagFirma = (Boolean) entity.getPropertyValue("flagFirma");
            boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
            boolean flagProtocolla = (Boolean) entity.getPropertyValue("flagProtocolla");
            
            logger.info("flagProtocolla after {}", flagProtocolla);
            logger.info("flagConserva after {}", flagConserva);
            logger.info("flagFirma after {}", flagFirma);
            logger.info("flagInviaMail after {}", flagInviaMail);
            
            String statoInvio = (String) entity.getPropertyValue("statoInvio");
            String statoInvioSec = (String) entity.getPropertyValue("statoInvioSec");
            logger.info("statoInvio {}", statoInvio);
            logger.info("statoInvioSec {}", statoInvioSec);
            String statoFirmato = (String) entity.getPropertyValue("statoFirmato");
            String statoConservazione = (String) entity.getPropertyValue("statoConservazione");
            String statoProtocolla = (String) entity.getPropertyValue("statoProtocolla");
            
            if(statoInvio != null && statoInvio.equals(statoOk) && (statoInvioSec != null && statoInvioSec.equals(statoOk))){
                flagInviaMail = false;
                entity.setProperty("flagInviaMail", false);
            }
            if(statoFirmato != null && statoFirmato.equals(statoOk)){
                flagFirma = false;
                entity.setProperty("flagFirma", false);
            }
            if(statoConservazione != null && statoConservazione.equals(statoOk)){
                flagConserva = false;
                entity.setProperty("flagConserva", false);
            }
            if(statoProtocolla != null && statoProtocolla.equals(statoOk)){
                flagProtocolla = false;
                entity.setProperty("flagProtocolla", false);
            }
            entity.persist();
            workSession.save();
            
            logger.info("flagProtocolla before {}", flagProtocolla);
            logger.info("flagConserva before {}", flagConserva);
            logger.info("flagFirma before {}", flagFirma);
            logger.info("flagInviaMail before {}", flagInviaMail);
           
            if(instanceId == null || taskId == null){
                StructuredProperty wfStr = entity.getStructuredProperty("workflow");
                List strRecords = wfStr.getRecords();
                for (int k = 0; k < strRecords.size(); k++) {
                    StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(k);
                    instanceId = "" + curRecord.getProperty("instanceId").getValue();
                    taskId = "" + curRecord.getProperty("taskId").getValue();
                }
            }
            
            workSession.getWorkflowManager().getWorkflowInstance(instanceId).setVariable("protocolla", flagProtocolla);
            workSession.getWorkflowManager().getWorkflowInstance(instanceId).setVariable("conserva", flagConserva);
            workSession.getWorkflowManager().getWorkflowInstance(instanceId).setVariable("firma", flagFirma);
            workSession.getWorkflowManager().getWorkflowInstance(instanceId).setVariable("invio", flagInviaMail);
            workSession.getWorkflowManager().getWorkflowInstance(instanceId).setVariable("fineManuale", false);
            
            entity.signal(instanceId, taskId);
        
        } catch (Exception ex) {
            logger.warn("Error executing action rule " + getClass().getName(), ex);
            throw new ModelException("Oops! Something went wrong: " + ex.getMessage(), ex);
            
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
        }
        
        return null;
    }
}
