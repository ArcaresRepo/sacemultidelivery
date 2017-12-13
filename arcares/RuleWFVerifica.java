package rules.arcares;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.module.mail.MailModuleImpl;

public class RuleWFVerifica implements WrRuleClassBody {
    //public class MyRule implements WrRuleClassBody {	

    WorkSession ws;
    Logger logger;
    Entity entity;
    
    public static String ACCETTAZIONE = "ACCETTAZIONE";
    public static String CONSEGNA = "CONSEGNA";
    public static String AVVISO_DI_MANCATA_CONSEGNA = "AVVISO DI MANCATA CONSEGNA";
    public static String AVVISO_DI_NON_ACCETTAZIONE = "AVVISO DI NON ACCETTAZIONE";
    public static String DELIVERY_NOTIFICATION = "Delivery Status Notification";
	

    public static String CERTIFICATO = "certificato";
    public static String ESTERNO = "esterno";

    private final String updateDBArcaresRuleName = "UpdateDBArcares";

    void setParameters(Map parameters) {
        logger.info("RuleWFVerifica -> setParameters...");
        ws = (WorkSession) parameters.get("workSession");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    @Override
    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RuleWFVerifica - Run : inizio");
        setParameters(parameters);
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        HashMap retMap = new HashMap();

        logger.info("RuleWFVerifica - Entity ID {}, Entity Type {}", entity.getId(), entity.getEntityType());

        try {
            //recuperare la mail in entrata
            String oggetto = (String) entity.getPropertyValue("subject");
            logger.info("RuleWFVerifica - Run : oggetto - " + oggetto);
            if (StringUtils.isNotBlank(oggetto) && (oggetto.startsWith(ACCETTAZIONE) || oggetto.startsWith(CONSEGNA) || oggetto.startsWith(AVVISO_DI_MANCATA_CONSEGNA) || oggetto.startsWith(AVVISO_DI_NON_ACCETTAZIONE) || oggetto.startsWith(DELIVERY_NOTIFICATION))) {
                logger.info("RuleWFVerifica - Run : sono nel ramo di una ricevuta");
                Entity mail = entity.getContainerEntity();
                logger.info("RuleWFVerifica - Container: " + mail.getEntityType());
                
                if(mail.getEntityType().equals(MailModuleImpl.OUTGOING_MAIL_NAME)){
                    if (oggetto.startsWith(ACCETTAZIONE)) {
                        salvaSuRicevute(mail);
                    }
                    if (oggetto.startsWith(CONSEGNA)) {
                        salvaSuRicevute(mail);
                    }

                    if (oggetto.startsWith(AVVISO_DI_NON_ACCETTAZIONE)) {
                        aggiornoDocumento(mail, AVVISO_DI_NON_ACCETTAZIONE);
                        salvaSuRicevute(mail);
                    }
                    if (oggetto.startsWith(AVVISO_DI_MANCATA_CONSEGNA)) {
                        aggiornoDocumento(mail, AVVISO_DI_MANCATA_CONSEGNA);
                        salvaSuRicevute(mail);
                    }
                    if (oggetto.startsWith(DELIVERY_NOTIFICATION)) {
                        aggiornoDocumento(mail, DELIVERY_NOTIFICATION);
                        salvaSuRicevute(mail);
                    }
                }                
            }

            return null;
        } catch (Exception ex) {
            logger.warn("RuleWFVerifica Error executing action rule " + getClass().getName(), ex);
            logger.error("RuleWFVerifica error: {}", ExceptionUtils.getMessage(ex));
            logger.error(ExceptionUtils.getStackTrace(ex));
            throw new RuntimeException("RuleWFVerifica Oops, something goes wrong: " + ex.getMessage(), ex);

        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
            logger.debug("RuleWFVerificaInvii - End");

        }
    }

    private void aggiornoDocumento(Entity outgoingMail, String errore) {
        // setto variabile inviato su comunicazione
        
        Entity entDocument = outgoingMail.getContainerEntity();
        String idFlusso = (String) entDocument.getPropertyValue("idFlusso");
        boolean principale = (Boolean) outgoingMail.getPropertyValue("principale");
        String idDocumento = (String) entDocument.getPropertyValue("idDocumento");
        Boolean sendMail = (Boolean) entDocument.getPropertyValue("flagInviaMail");
        if (sendMail) {
            // Aggiorna lo stato nel documento e nel database
            try {
                String update = null;
                aggiornaDocumento(idFlusso, idDocumento, principale, errore);
                if(principale){
                    entDocument.setProperty("statoInvio", "3");                  
                } else {                    
                    entDocument.setProperty("statoInvioSec", "3");  
                }                
                // cambiato da errore tecnico 4 a errore recuperabile 3
                entDocument.setProperty("stato", "3");  
                entDocument.persist();
                ws.save();

                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", update);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);

                logger.info("RuleWFVerifica: modificato lo stato del documento {} in {}",
                        entDocument.getPropertyValue("idDocumento"), errore != null ? "4" : "2");

            } catch (Exception e) {
                logger.warn("RuleWFVerifica Error: Executing action rule " + getClass().getName(), e);
                logger.error("RuleWFVerifica Error: {}", ExceptionUtils.getMessage(e));
                logger.debug(ExceptionUtils.getStackTrace(e));
            }
        }
    }
    
    private void aggiornaDocumento(String idFlusso, String idDocumento, boolean principale, String errorMessage) throws Exception{
        
        String colonnaDesc = "DES_ERRORE_DLVRY";
        String colonnaStato = "COD_STATO_DLVRY";
        if(!principale){
            colonnaDesc = "DES_ERR_DLVSEC";
            colonnaStato = "COD_STATO_DLVSEC";
        }
        
        // cambiato da errore tecnico 4 a errore recuperabile 3
        String update = "UPDATE WRDC_DETAIL SET COD_STATO = '3', " + colonnaDesc + " = '" + errorMessage + "', " + colonnaStato + " = '3' WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
        
        HashMap paramsUpdate = new HashMap();
        paramsUpdate.put("workSession", ws);
        paramsUpdate.put("logger", logger);
        paramsUpdate.put("query", update);
        logger.info("calling externalRule " + updateDBArcaresRuleName);
        WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
            
    }

    private void salvaSuRicevute(Entity outgoingMail) {
        Entity documento = outgoingMail.getContainerEntity();
        
        String idDocOnDB = (String) documento.getPropertyValue("idDocumento");
        String entityType = entity.getEntityType();
        String docType = documento.getEntityType();
        logger.info("RuleWFverifica: Id Documento: {}, Entity Type: {}", idDocOnDB, entityType);
        String insert = "INSERT INTO WRDC_RICEVUTE (ID_DOCUMENTO, ID_WR_CHIAVE, COD_CLASSE_DOC, COD_ENTITY_DOC) "
                    + "VALUES ('" + idDocOnDB + "', '" + entity.getId() + "', '" + entityType + "', '" + docType + "')";
        HashMap paramsUpdate = new HashMap();
        paramsUpdate.put("workSession", ws);
        paramsUpdate.put("logger", logger);
        paramsUpdate.put("query", insert);
        logger.info("calling externalRule " + updateDBArcaresRuleName);
        WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
    }
}
