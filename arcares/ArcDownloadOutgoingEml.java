package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.ContainedEntities;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.hi.utils.ResourcesCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Calendar;
import org.apache.commons.io.IOUtils;

import org.apache.commons.lang3.exception.ExceptionUtils;

public class ArcDownloadOutgoingEml implements WrRuleClassBody {

    WorkSession ws;
    Entity entity;
    Map docProperties;
    Logger logger;

    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

  
    public Object run(Map parameters) {
        byte[] retData = null;
        setParameters(parameters);

        boolean wasSkippingPolicies = ws.isSkippingPolicies();
       
        try {
            ws.setSkipPolicies(true);
            File cacheFile = null;
            
            Calendar calendar = Calendar.getInstance();
          //  ContainedEntities incomingMails = entity.getContainedEntities("outgoingMail");
          //    if(incomingMails != null && incomingMails.size() == 1){
          //        Entity incomingMail = (Entity) incomingMails.get(0);
                ContainedEntities originalMessages = entity.getContainedEntities("originalMessage");
                if(originalMessages != null && originalMessages.size() == 1){
                      logger.info("Scarica EML Id: {}", entity.getId());
             
                    Entity originalMessage = (Entity) originalMessages.get(0);
                    StructuredProperty resources = originalMessage.getStructuredProperty(Resource.RESOURCES_PROPERTY);
                    List strRecords = resources.getRecords();
                    if (strRecords.size() > 0) {
                         logger.info("Trovato EML  {}",strRecords.size() );
                        for (int i = 0; i < strRecords.size(); i++) {
                            StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(i);
                            InputStream data = (InputStream) curRecord.getPropertyValue("data");
                           
                            cacheFile = ResourcesCache.getInstance().createCachedFile("" + calendar.getTimeInMillis(), ".eml");
                            OutputStream outputStream = new FileOutputStream(cacheFile);
                            IOUtils.copy(data, outputStream);
                            outputStream.close();
                            retData = Files.readAllBytes(cacheFile.toPath());
                            break;
                        }
                    }
                }
            //}
            
        } catch (Exception ex) {
            
            logger.error("error: {}", ExceptionUtils.getMessage(ex));
            logger.error(ExceptionUtils.getStackTrace(ex));

            //throw new RuntimeException("Errore nell'invio in conservazione");
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
        }
        
        return retData;
    }
}
