package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.module.mail.MailModuleImpl;
import java.util.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import it.cbt.mail.ws.client.Ricezione;

/**
 *
 * @author Miniello
 */
public class RuleWFCheckChiusura implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    @Override
    public Object run(Map parameters) {
        setParameters(parameters);
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();

        try {
            workSession.setSkipPolicies(true);
            logger.info("RuleWFCheckChiusura");

            String messaggio = "transition";
            
            String value = "assign";//close
            
            /**
             * il workflow va fatto terminare solo per le ricevute figlie di una mail in uscita, per le buste
             */
            boolean isNotification = false, pecAnomaly = false, pec = false, isBusta = false;
            String ricevuta = "";
            Object tmp = entity.getPropertyValue("pecNotification");
            if(tmp != null){
                isNotification = (Boolean) tmp;
            }
            
            tmp = entity.getPropertyValue("pecRicezione");
            if(tmp != null){
                ricevuta = (String) tmp;
            }
            
            
            //se è una busta
            tmp = entity.getPropertyValue("pecAnomaly");
            if(tmp != null){
                pecAnomaly = (Boolean) tmp;
            }
            tmp = entity.getPropertyValue("pec");
            if(tmp != null){
                pec = (Boolean) tmp;
            }
            isBusta = pec || pecAnomaly;
            
            
            Entity parent = entity.getContainerEntity();
            
            if(parent.getEntityType().equalsIgnoreCase(MailModuleImpl.OUTGOING_MAIL_NAME) && (ricevuta.equalsIgnoreCase(Ricezione.ACCETTAZIONE.name()) || ricevuta.equalsIgnoreCase(Ricezione.AVVENUTA_CONSEGNA.name()))){
                value = "close";
            } else if(parent.getEntityType().equalsIgnoreCase(MailModuleImpl.INCOMING_MAIL_NAME)){
                value = "close";
            }
            
            HashMap retMap = new HashMap();
            retMap.put(messaggio, value);
            return retMap;

        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.debug("End");
        }

        
        return null;
    }

}
