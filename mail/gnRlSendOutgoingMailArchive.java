package rules.mail;

import it.cbt.mail.ws.client.MailAccountBean;
import it.cbt.mail.ws.client.MailServiceClient;
import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.ContainedEntities;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.module.mail.MailManager;
import it.cbt.wr.module.mail.MailModuleImpl;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.script.ScriptException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 * This rules is attached to an action in the draft state of an outgoingMail.
 * The code sends a mail using the given account and then ends its workflow,
 * archiving it.
 *
 * @author Luca Tagliani
 */
public class gnRlSendOutgoingMailArchive implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    String externalURL;
    Entity mail;
    Entity mailAccount;
    String instanceId;
    String taskId;
    private String originalMessageEntityType = "originalMessage";

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        mail = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
        mailAccount = (Entity) mail.getPropertyValue("mailAccount");
        instanceId = (String) parameters.get("instanceId");
        taskId = (String) parameters.get("taskId");

    }

    @Override
    public Object run(Map parameters) throws ScriptException {
        HashMap result = new HashMap();
        result.put("globalError", false);
        setParameters(parameters);
        Boolean isp = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
            //Controlli
            Entity accountMail = null;
            Object tmp = mail.getPropertyValue("mailAccount");
            if (tmp == null) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire l'account mail da utilizzare");
                return result;
            }

            accountMail = (Entity) (tmp);
            ArrayList list = (ArrayList) mail.getPropertyValue("to");
            if (list == null || list.size() == 0) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire il destinatario della mail");
                return result;
            }

            tmp = mail.getPropertyValue("body");
            if (tmp == null) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire il Corpo della mail");
                return result;
            }

            Object dInv = mail.getPropertyValue("sentDate");
            logger.info("dInv " + dInv);
            if (dInv == null) {
                Calendar todayCalendar = Calendar.getInstance();
                mail.setProperty("sentDate", todayCalendar);
                logger.info("Aggiunta data " + todayCalendar.getTime());
            }

            list = (ArrayList) mail.getPropertyValue("from");
            logger.info("from " + list.size());
            if (list == null || list.size() == 0) {
                //Recupero l'account usato per l'invio e lo valorizzo con l'indirizzo email dell'account
                tmp = accountMail.getPropertyValue("emailAddress");
                if (tmp == null) {
                    result.put("globalError", true);
                    result.put("errorMessage", "Indirizzo email Mittente non specificato nell'account configurato.");
                    return result;
                }
                list.add(tmp);
                mail.setProperty("from", list);

                logger.info("Aggiunto emailAddress " + tmp);
            }

            mail.persist();
            workSession.save();

            // get mailProxyWsdlUrl
            logger.info("Connessione a " + mailAccount.getPropertyValue("mailProxyWsdlUrl") + " in corso ...");
            String mailProxyWsdlUrl = (String) mailAccount.getPropertyValue("mailProxyWsdlUrl");
            String mailProxyUserName = (String) mailAccount.getPropertyValue("mailProxyWsdlUsername");
            String mailProxyPassword = (String) mailAccount.getPropertyValue("mailProxyWsdlPassword");
            // connect to remote service using MailManager
            logger.info("connect to remote service using MailManager");
            MailModuleImpl mailModule = (MailModuleImpl) workSession.getWorkspace().getInstalledModule("mail");
            logger.info("mail module retrieved");
            MailManager manager = mailModule.getMailManager(workSession, mailProxyWsdlUrl, mailProxyUserName, mailProxyPassword);
            logger.info("Connected to {}", mailProxyWsdlUrl);
            MailServiceClient client = manager.getMailServiceClient();
            MailAccountBean account = client.getMailAccountFromName((String) mailAccount.getPropertyValue("emailAddress"));
            logger.info("Account retrieved {}", account.getName());

            //Invio la mail e ottengo il messageId e l'eml
            List<String> sendResult = manager.sendMail(mail, account);
            String messageId = (String) sendResult.get(0);
            logger.info("messageId {}", messageId);

            Object tmpEml = sendResult.get(1);
            if (tmpEml != null) {
                String originalMessageString = (String) tmpEml;
                if (!originalMessageString.isEmpty()) {
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    File emlFile = null;
                    try {
                        //Creo un file per l'eml
                        emlFile = File.createTempFile("OriginalEml", ".eml");
                        // convert String into InputStream

                        inputStream = new ByteArrayInputStream(originalMessageString.getBytes());
                        outputStream = new FileOutputStream(emlFile);
                        int read = 0;
                        byte[] bytes = new byte[1024];

                        while ((read = inputStream.read(bytes)) != -1) {
                            outputStream.write(bytes, 0, read);
                        }
                    } finally {
                        if (inputStream != null) {
                            try {
                                inputStream.close();
                            } catch (Exception e) {
                                logger.error("Errore nella chiusura dell'inputStream", e);
                            }
                        }
                        if (outputStream != null) {
                            try {
                                // outputStream.flush();
                                outputStream.close();
                            } catch (Exception e) {
                                logger.error("Errore nella chiusura dell'outputstream", e);
                            }

                        }
                    }

                    //Creiamo L'original Message
                    ContainedEntities containedEntities = mail.getContainedEntities("originalMessage");
                    if(containedEntities == null || containedEntities.isEmpty()){
                        Entity originalMessage = workSession.createNewEntity(originalMessageEntityType, mail);
                        originalMessage.setProperty("name", "OriginalMessage");

                        originalMessage.persist();
                        workSession.save();

                        if (emlFile != null) {

                            logger.info("Entity Original Message Created: " + originalMessage.getId());
                            addResource(emlFile, "message/rfc822", originalMessage);
                            emlFile.delete();
                        }
                    }
                }
            }
            mail.setProperty("messageId", messageId);
            mail.setProperty("errorMessage", "");
            mail.persist();
            workSession.save();

            //All goes well
            if(taskId == null || instanceId == null){
                 StructuredProperty wfStr = mail.getStructuredProperty("workflow");
                 List strRecords = wfStr.getRecords();
                 StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(0);
                 instanceId = "" + curRecord.getProperty("instanceId").getValue();
                 taskId = "" + curRecord.getProperty("taskId").getValue();

            }
            
            // let's signal the mail to archive it
            logger.info("Signal Mail {} {}", instanceId, taskId);
            logger.info("Mail Id {}", mail.getId());
            
            mail.signal(instanceId, taskId);
            workSession.save();
            // then move it under the correct folder
            String containerType = mail.getContainerEntity().getEntityType();
            if (containerType.equals("mailFolder")) {
                manager.moveMail(mail, "Outgoing");
            }
            result.put("entityId", mail.getId());
        } catch (Exception ex) {
            logger.error("Gestione errore invio Mail...");
            logger.error(ExceptionUtils.getStackTrace(ex));

            String errorMsg = ex.getMessage();
            errorMsg = errorMsg.substring(errorMsg.indexOf(":") + 1);
            if (errorMsg.indexOf("Invalid email") > -1) {
                errorMsg = "Indirizzo Email non valido, controllare i dati e riprovare.";
            } else if (errorMsg.indexOf("Sending failed, attachments") > -1 || errorMsg.indexOf("Sending failed, file") > -1) {
                errorMsg = "Controllare gli allegati e riprovare.";
            } else {
                errorMsg = "Errore nell''invio della mail, contattare l''amministratore.";
            }
            logger.error(errorMsg + " per mail: " + mail.getId());
            mail.setProperty("errorMessage", errorMsg);
            
            try {
                mail.persist();
                workSession.save();
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.error(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in gnRlSendOutgoingMailArchive (ATTENZIONE STATO INVIO NON AGGIORNATO IN ERRORE SU WR!!!!): " + e.getMessage(), e);
            }
            result.put("globalError", true);
            result.put("errorMessage", errorMsg);
        } finally {
            workSession.setSkipPolicies(isp);
        }
        return result;
    }

    private void addResource(File file, String mime, Entity mailEntity) throws Exception {
        StructuredProperty structuredProperty = mailEntity.getStructuredProperty(Resource.RESOURCES_PROPERTY);
        StructuredPropertyRecord record = structuredProperty.createRecord();

        String fileName = file.getName();
        record.setProperty("data", new FileInputStream(file));
        record.setProperty("mimeType", mime);
        record.setProperty("encoding", "UTF-8");
        record.setProperty("originalFilename", mailEntity.getPropertyValue("name"));
        record.setProperty("fileSize", FileUtils.sizeOf(file));
        record.setProperty("fileExtension", fileName.substring(fileName.lastIndexOf(".") + 1));
        structuredProperty.addRecord(record);
        mailEntity.persist();
    }

}
