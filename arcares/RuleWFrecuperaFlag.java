package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 *
 * @author
 */
public class RuleWFrecuperaFlag implements WrRuleClassBody {

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
        HashMap retMap = new HashMap();

        setParameters(parameters);

        logger.info("Start");
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);

            List resourceList = entity.getStructuredProperty("resources").getRecords();
            
            if (resourceList == null || resourceList.isEmpty()) {
            	// La recuperaDocumenti non ha associato risorse al documento
            	logger.info("Nessun File associato al documento !! Entro in Errore Tecnico ");
            	String filepath = (String ) entity.getPropertyValue("docPath").toString();
            	if (filepath != null ) {            		
            		logger.info("File Path recuperato :" + filepath.toString());
            		File fi = new File(filepath);
            		if (fi.exists()) {
            			logger.info("File esiste provo ad aggiungerlo alle resources ... ");
            			// Provo ad aggiungere il file alle resources           			
            			StructuredProperty resources = entity.getStructuredProperty(Resource.RESOURCES_PROPERTY);
            	        
            	        FileInputStream fileInputStream = new FileInputStream(fi);
            	        StructuredPropertyRecord newRecord = resources.createRecord();
            	        newRecord.setProperty("fileExtension", FilenameUtils.getExtension(filepath));
            	        newRecord.setProperty("description", fi.getName());
            	        newRecord.setProperty("data", fileInputStream);
            	        newRecord.setProperty("originalFilename", fi.getName());

            	        try {
            	            Files.probeContentType(fi.toPath());
            	            String tikaType = Files.probeContentType(fi.toPath());
            	            newRecord.setProperty("mimeType", tikaType);
            	            resources.addRecord(newRecord);
            	            
            	        } catch (IOException e) {
            	        	logger.error("In eccezione su mimeType recupero su " + fi.toPath());
            	            e.printStackTrace();
            	        }      			           			
            			
            		} else {
            			entity.setProperty("stato", "4");
            			
            			throw new Exception("ERRORE in RuleWFrecuperaFlag DOCUMENTO NON PRESENTE !! ");
            		}
            		
            	}
            	
            	entity.persist();
                workSession.save();
            	
            	
            }
            
            Boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
            Boolean flagFirma = (Boolean) entity.getPropertyValue("flagFirma");
            Boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
            Boolean flagProtocolla = (Boolean) entity.getPropertyValue("flagProtocolla");
            Boolean flagMarca = (Boolean) entity.getPropertyValue("flagMarca");

            retMap.put("conserva", flagConserva);
            retMap.put("firma", flagFirma);
            retMap.put("invio", flagInviaMail);
            retMap.put("protocolla", flagProtocolla);
            retMap.put("marca", flagMarca);

            //retMap.put("tipoInvio", entity.getPropertyValue("tipoInvio"));
            retMap.put("tipoInvio", "mail");
            retMap.put("blocca", false);

            // CASO PARTICOLARE
            // Se tutti i flag sono a N metti COD_STATO a 2 (Nessuna elaborazione)
            if (!flagConserva && !flagFirma && !flagInviaMail && !flagProtocolla) {
                entity.setProperty("stato", "2");
                entity.persist();
                workSession.save();
                String update = "UPDATE WRDC_DETAIL "
                        + "SET COD_STATO = '2', COD_ERRORE='', "
                        + "COD_STATO_ARCH='2', COD_ERRORE_ARCH='', "
                        + "COD_STATO_FIRM='2', COD_ERRORE_FIRM='',  "
                        + "COD_STATO_DLVRY='2', COD_ERRORE_DLVRY='',  "
                        + "COD_STATO_MARCA='2', COD_ERRORE_MARCA=''  "
                        + "COD_STATO_PROT='2', COD_ERRORE_PROT=''  "
                        + "WHERE ID_FLUSSO = " + entity.getPropertyValue("idFlusso")
                        + " AND ID_DOCUMENTO = '" + entity.getPropertyValue("idDocumento") + "'";
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", workSession);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", update);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);
            }

        } catch (Exception e) {
        	
        	entity.persist();
            workSession.save();
            logger.error("error: {}", ExceptionUtils.getMessage(e));        
            throw new RuntimeException("ERRORE in RuleWFrecuperaFlag DOCUMENTO NON PRESENTE !! ");
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.debug("End");
        }
        return retMap;
    }
}
