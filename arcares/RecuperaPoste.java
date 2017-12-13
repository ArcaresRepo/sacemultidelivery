package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class RecuperaPoste implements WrRuleClassBody {
    WorkSession ws; // Required
    Logger logger;	// Required
    Entity entity;	// Required
    
    void setParameters(Map parameters) {
        logger.info("RecuperaRicevute: setParameters...");
        ws = (WorkSession) parameters.get("workSession"); 
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);        
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RecuperaRicevute: Inizio");
        setParameters(parameters);        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
         
        HashMap retMap = new HashMap();
        
        try {
            ws.setSkipPolicies(Boolean.TRUE); 
            boolean raccomandata = (Boolean) entity.getPropertyValue("raccomandata");
            String ruleName = "RecuperLettera";
            if(raccomandata){
                ruleName = "RecuperaRicevuta";
            }
            ArrayList entitiesT = new ArrayList();
            entitiesT.add(entity);
            HashMap params = new HashMap();
            params.put("entities", entitiesT);//Action.ENTITIES_PARAMETER
            params.put("workSession", ws);
            params.put("logger", logger);
            logger.info("calling externalRule " + ruleName);
            retMap = (HashMap) WrApiUtils.evaluateRule(ws, ruleName, params);
            
        } catch (Exception ex) {
            logger.warn("RecuperaRicevute Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RecuperaRicevute Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));
            
            throw new RuntimeException("ERRORE IN RecuperaRicevute: " + ex.getMessage(), ex);
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);    
            logger.info("RecuperaRicevute: End");
        }
        return retMap;
    }    
}

