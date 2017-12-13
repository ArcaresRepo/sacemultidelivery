package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.rolservice.CEResult;
import it.cbt.wr.rolservice.Destinatario;
import it.cbt.wr.rolservice.ROLServiceSoap;
import it.cbt.wr.rolservice.RecuperaDestinatariResult;
import it.cbt.wr.rolservice.Richiesta;
import it.cbt.wr.rolservice.client.ROLClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class RecuperaRicevute implements WrRuleClassBody {
    WorkSession ws; // Required
    Logger logger;	// Required

    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    
    void setParameters(Map parameters) {
        logger.info("RecuperaRicevute: setParameters...");
        ws = (WorkSession) parameters.get("workSession");       
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RecuperaRicevute: Inizio");
        setParameters(parameters);        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
 
        
        try {
            ws.setSkipPolicies(Boolean.TRUE); 
            
            QueryManager qm = ws.getQueryManager();
            String query = "//element(*,documentoPostale)[(@raccomandata = 'true' and @ricevuta = 'false' and @pecUniqueId)] order by @jcr:score";
            
            StringBuilder optProt = new StringBuilder().append("/jcr:root").append("//element(*,opzioniPoste)");
            List<Entity> optionList = qm.executeQuery(optProt.toString()).getResults();
            Entity option;
            if(optionList.size()==1){
                 option = (Entity) optionList.get(0);
            }else{
                throw new Exception("Errore nel recupero dell'entità opzioniPoste");
            }		

            String url = (String) option.getPropertyValue("url");
            String user = (String) option.getPropertyValue("user");
            String password = (String) option.getPropertyValue("password");
            
            ROLClient rolClient = new ROLClient();            
            ROLServiceSoap rolService = rolClient.bindToWebServices(url + "/ROLGC/RolService.svc", user, password);
            
            List<Entity> documentiPostali = qm.executeQuery(query).getResults();
            for (int i = 0; i < documentiPostali.size(); i++) {
                Entity docPostale = ((Entity) documentiPostali.get(i)).getContainerEntity();
                String idRichiesta = (String) docPostale.getPropertyValue("pecUniqueId");
                logger.info("idRichiesta: "+idRichiesta);
                Richiesta richiesta = new Richiesta();
                richiesta.setIDRichiesta(idRichiesta);
                richiesta.setGuidUtente(query);
                
                
                RecuperaDestinatariResult recDestinatari = rolService.recuperaDestinatari(richiesta);
                CEResult ceResult = recDestinatari.getCEResult();
                if (ceResult.getType().equals("I")) {
                    List<Destinatario> destinatari = recDestinatari.getDestinatari().getDestinatario();
                    for(Destinatario dest : destinatari){
                        String idRicevuta = dest.getIdRicevuta();
                        if(idRicevuta != null && !idRicevuta.isEmpty()){
                            docPostale.setProperty("ricevuta", true);
                            docPostale.setProperty("idRicevuta", idRicevuta);
                            docPostale.persist();
                            ws.save();
                            
                            if (docPostale.getEntityType().equals("header")) {
                                List documenti = docPostale.getContainedEntities("documento");

                                for (int cont = 0; cont < documenti.size(); cont++) {
                                    Entity entDocument = (Entity) documenti.get(cont);

                                    String idDocumento = (String) entDocument.getPropertyValue("idDocumento");

                                    // Individua i documenti da inviare per email
                                    Boolean sendMail = (Boolean) entDocument.getPropertyValue("flagInviaMail");
                                    if (sendMail) {
                                        salvaSuRicevute(idDocumento, docPostale);

                                    }
                                }
                            }
                        }
                    }
                    
                }
            }
                    
        } catch (Exception ex) {
            logger.warn("RecuperaRicevute Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RecuperaRicevute Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));


        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);    
            logger.info("RecuperaRicevute: End");
        }
        return null;
    }
    
    private void salvaSuRicevute(String idDocOnDB, Entity entityRef) {
        String entityId = entityRef.getId();
        String entityType = "CartolinaPostale";
        logger.info("RuleWFverifica: Id Documento: {}, Entity ID: {}, Entity Type: {}", idDocOnDB, entityId, entityType);
        String insert = "INSERT INTO table_name (ID_DOCUMENTO, ID_WR_CHIAVE, COD_CLASSE_DOC) "
                + "VALUES ('" + idDocOnDB + "', '" + entityId + "', '" + entityType + "')";
        HashMap paramsUpdate = new HashMap();
        paramsUpdate.put("workSession", ws);
        paramsUpdate.put("logger", logger);
        paramsUpdate.put("query", insert);
        logger.info("calling externalRule " + updateDBArcaresRuleName);
        WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);        
    }
}

