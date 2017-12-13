package rules;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.definitions.EntityTypeDefinition;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.HttpEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import it.cbt.wr.module.digitalsign.DigitalSignManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpResponse;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;

public class Conserva implements WrRuleClassBody {

    WorkSession ws;
    Entity entity;
    Map docProperties;
    Logger logger;
    Entity option;
    InputStream content;
    String contentName;
    private String WSDL_URL;
    private String CONSERV_URL;

    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
        content = (InputStream) parameters.get("content");
        contentName = (String) parameters.get("contentName");
    }

    private final int GET = 1, POST = 2, PUT = 3, DELETE = 4;
    private boolean deleteTempFiles = false;
    String loginToken = null;
    private String POLICY_ID;
    private String DOCUMENT_CLASS;
    private String DOCCLASS_LABEL;
    private String PATH_CONSERVAZIONE;
    private String WEB_SERVICE_URL;

    HttpResponse callWS(String url, int wsMethods, String mediaType, String acceptType, HttpEntity entity, String ldSessionId) {
        try {
            HttpRequestBase baseRequest = null;
            switch (wsMethods) {
                case GET:
                    baseRequest = new HttpGet(url);
                    break;

                case POST:
                    baseRequest = new HttpPost(url);
                    if (entity != null) {
                        ((HttpPost) baseRequest).setEntity(entity);
                    }
                    break;

                case PUT:
                    baseRequest = new HttpPut(url);
                    if (entity != null) {
                        ((HttpPut) baseRequest).setEntity(entity);
                    }
                    break;

                case DELETE:
                    baseRequest = new HttpDelete(url);
                    break;

            }
            if (baseRequest == null) {
                throw new IOException("Null request");
            }
            if (mediaType != null) {
                baseRequest.setHeader("Content-Type", mediaType);
            }
            if (acceptType != null) {
                baseRequest.setHeader("Accept", acceptType);
            }
            baseRequest.addHeader("ldSessionId", ldSessionId);
            //da settare per l'esibizione multipart
            //baseRequest.addHeader(“Accept”, “multipart/mixed”);
            DefaultHttpClient httpclient = new DefaultHttpClient();
            return httpclient.execute(baseRequest);
        } catch (IOException e) { //gestire eccezione}
            logger.error("Eccezione durante l'esecuzione della richiesta", e);
            return null;
        } catch (Exception e) { //gestire eccezione}
            logger.error("Eccezione durante l'esecuzione della richiesta", e);
            return null;
        }
    }

    // Effettua l'hash di una stringa
    String hashString(String message, String algorithm) {
        String hashString = null;
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashedBytes = digest.digest(message.getBytes("UTF-8"));
            hashString = javax.xml.bind.DatatypeConverter.printHexBinary(hashedBytes);
        } catch (UnsupportedEncodingException ex) {
            logger.error("Error calculating HASH of [" + message + "]", ex);
        } catch (NoSuchAlgorithmException ex) {
            logger.error("Error calculating HASH with [" + algorithm + "]", ex);
        } catch (Exception ex) {
            logger.error("Error calculating HASH with [" + algorithm + "]", ex);
        }
        return hashString;
    }

    // Calcola l'hash di un filestream
    private String hashFile(InputStream is, String algorithm) {
        String hashString = null;
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[1024];
            int bytesRead = -1;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashedBytes = digest.digest();
            hashString = javax.xml.bind.DatatypeConverter.printHexBinary(hashedBytes);
        } catch (IOException ex) {
            logger.error("Error calculating HASH of strem", ex);
        } catch (NoSuchAlgorithmException ex) {
            logger.error("Error calculating HASH with [" + algorithm + "]", ex);
        } catch (Exception ex) {
            logger.error("Error calculating HASH with [" + algorithm + "]", ex);
        } finally {
            try {
                is.close();
            } catch (IOException ex) {
                logger.warn("Error closing file...", ex);
            }
        }
        return hashString;
    }

    private File getWorkingDir() {
        Calendar ora = Calendar.getInstance();
        File temporaryDir = new File(System.getProperty("java.io.tmpdir"),
                "" + ora.getTimeInMillis());
        logger.info("Temporary working dir: {}", temporaryDir.getPath());
        temporaryDir.mkdirs();
        return temporaryDir;
    }

    private void writeStreamToFile(InputStream source, File dstFile) {
        OutputStream outStm = null;
        try {
            outStm = new BufferedOutputStream(new FileOutputStream(dstFile));
            IOUtils.copy(source, outStm);
        } catch (Exception ex) {
            logger.error("Error copying stream ...", ex);
        } finally {
            if (outStm != null) {
                IOUtils.closeQuietly(outStm);
            }
            IOUtils.closeQuietly(source);
        }
    }

    private List<File> getDataFiles(File workDir) throws Exception {
        List dataFiles = new ArrayList();
        List resourceList = entity.getStructuredProperty("resources").getRecords();
        if (content != null && contentName != null) {
            File tmpDataFile = new File(workDir, contentName);
            logger.info("dataFile #: {}", tmpDataFile.getPath());

            writeStreamToFile(content, tmpDataFile);
            dataFiles.add(tmpDataFile);
        } else {
            for (int j = 0; j < resourceList.size(); j++) {
                StructuredPropertyRecord oRes = (StructuredPropertyRecord) resourceList.get(j);
                String curDocFileName = (String) oRes.getPropertyValue("originalFilename");

                File tmpDataFile = new File(workDir, curDocFileName);
                logger.info("dataFile #{}: {}", j, tmpDataFile.getPath());

                InputStream inStream = (InputStream) oRes.getPropertyValue("data");
                writeStreamToFile(inStream, tmpDataFile);
                dataFiles.add(tmpDataFile);
                break;
            }
        }
        return dataFiles;
    }

    private File createIndexFile(File workDir, Map docProps) throws Exception {
        StringBuilder indexTxt = new StringBuilder();
        indexTxt.append("<?xml version='1.0' encoding='utf-8'?>\r\n");
        indexTxt.append("<legaldocIndex xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'");
        indexTxt.append(" xmlns:xsd='http://www.w3.org/2001/XMLSchema'");
        indexTxt.append(" documentClass='" + DOCUMENT_CLASS + "'");
        indexTxt.append(" label='" + DOCCLASS_LABEL + "'>\r\n");

        logger.debug("Loop sulle chiavi della mappa...");
        Iterator it = docProps.keySet().iterator();
        while (it.hasNext()) {
            String propName = (String) it.next();
            String propValue = StringEscapeUtils.escapeXml((String) docProps.get(propName));
            indexTxt.append("\t<field name='");
            indexTxt.append(propName);
            indexTxt.append("'>");
            indexTxt.append(propValue);
            indexTxt.append("</field>\r\n");
        }
        indexTxt.append("</legaldocIndex>");

        File tmpIndexFile = new File(workDir, "index.xml");
        logger.info("indexFile: " + tmpIndexFile.getPath());
        InputStream inStream = new ByteArrayInputStream(indexTxt.toString().getBytes("UTF-8"));
        writeStreamToFile(inStream, tmpIndexFile);
        return tmpIndexFile;
    }

    private String getMimeType(File dataFile) throws Exception {
        Files.probeContentType(dataFile.toPath());
        String mimeType = Files.probeContentType(dataFile.toPath());
        return mimeType;
    }

    private File createParamFile(File workDir, File indexFile, List dataFiles) throws Exception {
        StringBuilder commandTxt = new StringBuilder();
        commandTxt.append("<?xml version='1.0' encoding='utf-8'?>\r\n");
        commandTxt.append("<parameters xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'");
        commandTxt.append(" xmlns:xsd='http://www.w3.org/2001/XMLSchema'>\r\n");
        commandTxt.append("\t<policy_id>" + POLICY_ID + "</policy_id>\r\n");
        commandTxt.append("\t<index_file>\r\n");
        commandTxt.append("\t\t<index_name>index.xml</index_name>\r\n");
        commandTxt.append("\t\t<index_hash>");
        commandTxt.append(hashFile(new FileInputStream(indexFile), "SHA-256"));
        commandTxt.append("</index_hash>\r\n");
        commandTxt.append("\t\t<index_mimetype>text/xml;1.0</index_mimetype>\r\n");
        commandTxt.append("\t</index_file>\r\n");
        for (int i = 0; i < dataFiles.size(); i++) {
            File tmpDataFile = (File) dataFiles.get(i);
            commandTxt.append("\t<data_file>\r\n");
            commandTxt.append("\t\t<data_name>");
            Calendar calendar = Calendar.getInstance();
            commandTxt.append(calendar.getTimeInMillis() + "_" + tmpDataFile.getName());
            commandTxt.append("</data_name>\r\n");
            commandTxt.append("\t\t<data_hash>");
            commandTxt.append(hashFile(new FileInputStream(tmpDataFile), "SHA-256"));
            commandTxt.append("</data_hash>\r\n");
            commandTxt.append("\t\t<data_mimetype>");
            commandTxt.append(getMimeType(tmpDataFile));
            commandTxt.append(";NA</data_mimetype>\r\n");
            commandTxt.append("\t</data_file>\r\n");
        }
        commandTxt.append("\t<path>" + PATH_CONSERVAZIONE + "</path>\r\n");
        commandTxt.append("</parameters>");
        File tmpParamFile = new File(workDir, "conserve.xml");
        logger.info("paramFile: " + tmpParamFile.getPath());
        InputStream inStream = new ByteArrayInputStream(commandTxt.toString().getBytes("UTF-8"));
        writeStreamToFile(inStream, tmpParamFile);
        return tmpParamFile;
    }

    private Document getDocumentXML(String xmlTextDocument) throws Exception {
        String errorDesc = null;
        DocumentBuilderFactory fact = DocumentBuilderFactory.newInstance();
        fact.setNamespaceAware(true);
        try {
            DocumentBuilder docBilder = fact.newDocumentBuilder();
            Document xmlDoc = docBilder.parse(new ByteArrayInputStream(xmlTextDocument.getBytes("UTF-8")));
            return xmlDoc;
        } catch (ParserConfigurationException ex) {
            errorDesc = "[ParserConfigurationException]";
            logger.error(errorDesc, ex);
        } catch (UnsupportedEncodingException ex) {
            errorDesc = "[UnsupportedEncodingException]";
            logger.error(errorDesc, ex);
        } catch (SAXException ex) {
            errorDesc = "[SAXException]";
            logger.error(errorDesc, ex);
        } catch (IOException ex) {
            errorDesc = "[IOException]";
            logger.error(errorDesc, ex);
        } catch (Exception ex) {
            errorDesc = "[Exception]";
            logger.error(errorDesc, ex);
        }
        throw new Exception(errorDesc);
    }

    private String getErrorFromXML(Document xmlDoc) {
        NodeList list = xmlDoc.getElementsByTagName("description");
        String errorDesc = list.item(0).getTextContent();
        logger.debug("Descrizione errore: {}", errorDesc);
        list = xmlDoc.getElementsByTagName("code");
        errorDesc += " [" + list.item(0).getTextContent() + "]";
        return errorDesc;
    }

    // esegui il login su legal doc
    private String executeLogin(String url, String userid, String password) throws Exception {
        logger.info("Eseguo il login a legalDoc [{}]...", url);
        List nameValuePairs = new ArrayList();
        nameValuePairs.add(new BasicNameValuePair("userid", userid));
        nameValuePairs.add(new BasicNameValuePair("password", password));

        logger.info("callWS...");
        HttpResponse response = callWS(url, POST, "application/x-www-form-urlencoded",
                null, new UrlEncodedFormEntity(nameValuePairs), "");
        HttpEntity resEntity = response.getEntity();
        String result = EntityUtils.toString(resEntity);
        logger.info("Result: " + result);

        Document xmlDoc = getDocumentXML(result);
        logger.info("Result code: {}", response.getStatusLine().getStatusCode());
        if (response.getStatusLine().getStatusCode() >= 300) {
            logger.warn("Login fallito.");
            throw new Exception(getErrorFromXML(xmlDoc));
        }
        logger.info("Login eseguito con successo.");

        NodeList list = xmlDoc.getElementsByTagName("LDSessionId");
        Node node = list.item(0).getFirstChild();
        String textResult = node.getTextContent();
        logger.info("Login token: {}", textResult);
        return textResult;
    }

    private void doLogout(String resourceUrl, String sessionId) {
        HttpResponse response = callWS(resourceUrl, DELETE,
                null, null, null, sessionId);
        logger.info("Logout response: " + response.toString());
    }

    private String conserve(String url, String sessionId,
            Entity entToConserve, Map docProps) throws Exception {
        File outXml = null;
        File outP7m = null;
        File indexFile = null;
        File paramsFile = null;
        List dataFiles = new ArrayList();
        File workDir = getWorkingDir();
        try {
            dataFiles = getDataFiles(workDir);
            indexFile = createIndexFile(workDir, docProps);
            paramsFile = createParamFile(workDir, indexFile, dataFiles);

            logger.debug("Call to conserve ... ");
            MultipartEntity requestEntity = new MultipartEntity();
            requestEntity.addPart("PARAMFILE", new FileBody(paramsFile));
            requestEntity.addPart("INDEXFILE", new FileBody(indexFile));
            for (int i = 0; i < dataFiles.size(); i++) {
                File tmpDataFile = (File) dataFiles.get(i);
                requestEntity.addPart("DATAFILE", new FileBody(tmpDataFile));
            }

            logger.debug("callWS...");
            HttpResponse response = callWS(url, POST, null, null,
                    requestEntity, sessionId);
            HttpEntity resEntity = response.getEntity();

            logger.info("Result code: {}", response.getStatusLine().getStatusCode());
            if (response.getStatusLine().getStatusCode() >= 300) {
                String result = EntityUtils.toString(resEntity);
                logger.info("Result: " + result);

                Document xmlDoc = getDocumentXML(result);
                logger.warn("Chiamata di conservazione fallita.");
                throw new Exception(getErrorFromXML(xmlDoc));
            }

            logger.info("Documento conservato.");
            InputStream input = response.getEntity().getContent();
            outP7m = new File(workDir, "ResCons.p7m");
            logger.info("Signed response: " + outP7m.getPath());
            writeStreamToFile(input, outP7m);

            outXml = new File(workDir, "ResCons.xml");
            logger.info("Response file: " + outXml.getPath());
            DigitalSignManager manager = DigitalSignManager.getInstance();
            input = manager.getContent(outP7m);

            ByteArrayOutputStream xmlStrem = new ByteArrayOutputStream();
            IOUtils.copy(input, xmlStrem);

            Document xmlDoc = getDocumentXML(xmlStrem.toString("UTF-8"));
            xmlStrem.close();
            input.close();

            NodeList list = xmlDoc.getElementsByTagName("ID");
            Node node = list.item(0).getFirstChild();
            String idDocument = node.getNodeValue();
            logger.info("Document token: {}", idDocument);
            return idDocument;

        } finally {
            if (deleteTempFiles) {
                if (outXml != null && outXml.exists()) {
                    logger.debug("Deleting response file: {}", outXml.getPath());
                    outXml.delete();
                }
                if (outP7m != null && outP7m.exists()) {
                    logger.debug("Deleting signed response file: {}", outP7m.getPath());
                    outP7m.delete();
                }
                for (int i = 0; i < dataFiles.size(); i++) {
                    File tmpDataFile = (File) dataFiles.get(i);
                    if (tmpDataFile != null && tmpDataFile.exists()) {
                        logger.info("Deleting data file: {}", tmpDataFile.getPath());
                        tmpDataFile.delete();
                    }
                }
                if (indexFile != null && indexFile.exists()) {
                    logger.info("Deleting index file: {}", indexFile.getPath());
                    indexFile.delete();
                }
                if (paramsFile != null && paramsFile.exists()) {
                    logger.info("Deleting parametrrs file: {}", paramsFile.getPath());
                    paramsFile.delete();
                }
                logger.info("Deleting working directory: {}", workDir.getPath());
                workDir.delete();
            } else {
                logger.info("Temporary folder: {}", workDir.getPath());
            }
        }
    }

    public Object run(Map parameters) {
        String esito = "";
        File currDir = null;
        HashMap result = new HashMap();
        result.put("globalError", false);

        setParameters(parameters);

        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        try {
            ws.setSkipPolicies(true);

            EntityTypeDefinition entityType = entity.getEntityTypeDefinition();
            String query = "//element(*, opzioniConservazione)[@entityType = '" + entityType.getId() + "']";
            List optionList = ws.getQueryManager().executeQuery(query).getResults();

            if (optionList.size() == 1) {
                option = (Entity) optionList.get(0);
            } else {
                logger.error("Entity opzioni non reperibile");
                result.put("globalError", true);
                result.put("errorMessage", "Template necessario per creazione del documento non presente, contattare l'assistenza");
                return result;
            }

            String username = (String) option.getPropertyValue("username");
            String userpwd = (String) option.getPropertyValue("userpwd");
            String bucket = (String) option.getPropertyValue("bucket");
            DOCUMENT_CLASS = (String) option.getPropertyValue("documentClass");
            DOCCLASS_LABEL = (String) option.getPropertyValue("docclassLabel");
            POLICY_ID = (String) option.getPropertyValue("policyId");
            PATH_CONSERVAZIONE = (String) option.getPropertyValue("pathConservazione");
            WEB_SERVICE_URL = (String) option.getPropertyValue("webServiceURL");

            logger.info("Credenziali: {}/{} [{}}", username, userpwd, bucket);
            logger.info("Document class: {} -> {} [{}]", DOCUMENT_CLASS, DOCCLASS_LABEL);
            logger.info("Policy: {} ", POLICY_ID);
            logger.info("Percorso di conservazione: {}", PATH_CONSERVAZIONE);

            WSDL_URL = WEB_SERVICE_URL + "/ws/session";
            CONSERV_URL = WEB_SERVICE_URL + "/ws/" + bucket + "/document";

            logger.debug("Loop sulle chiavi della mappa...");

            docProperties = new HashMap();
            StructuredProperty strP = option.getStructuredProperty("metadati");
            List< StructuredPropertyRecord> strRecords = strP.getRecords();
            for (int i = 0; i < strRecords.size(); i++) {
                StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(i);
                Entity prop = (Entity) curRecord.getPropertyValue("property");
                String propName = (String) prop.getPropertyValue("name");
                logger.info("propName: {} ", propName);
                Object obj = entity.getPropertyValue(propName);
                logger.info("obj: {} ", obj);
                String docClassMetaName = "" + curRecord.getPropertyValue("docClassMetaName");
                logger.info("docClassMetaName: {} ", docClassMetaName);

                String stringProp = null;
                if (obj instanceof String) {
                    stringProp = (String) obj;
                } else if (obj instanceof Calendar) {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");
                    Calendar data = (Calendar) obj;
                    stringProp = sdf.format(data.getTime());
                } else if (obj instanceof Long) {
                    Long longValue = (Long) obj;
                    stringProp = Long.toString(longValue);
                } else if (obj instanceof Double) {
                    Double doubleValue = (Double) obj;
                    stringProp = Double.toString(doubleValue);
                } // Aggiunta per gestire la proprietà to (+ destinatari)
                else if (obj instanceof List) {
                    List<String> toMails = (List<String>) obj;
                    for (String toMail : toMails) {
                        stringProp = toMail;
                        stringProp = stringProp.concat(";");
                    }
                }
                docProperties.put(docClassMetaName, stringProp);
            }

            Iterator it = docProperties.keySet().iterator();
            while (it.hasNext()) {
                String propName = (String) it.next();
                String propValue = (String) docProperties.get(propName);
                logger.info("-> {} = {}", propName, propValue);
            }

            loginToken = executeLogin(WSDL_URL, username, userpwd);

            logger.info("CONSERV_URL: {}", CONSERV_URL);
            logger.info("loginToken: {}", loginToken);
            logger.info("entity: {}", entity.toString());
            logger.info("docProperties: {}", docProperties.toString());

            esito = conserve(CONSERV_URL, loginToken, entity, docProperties);

            logger.info("esito: {} ", esito);

            result.put("id", entity.getId());
            result.put("token", esito);
            result.put("stato", "Conservato");

        } catch (Exception ex) {
            if (ex.getLocalizedMessage().contains("LD_FD101")) {
                try {
                    if (ex.getLocalizedMessage().contains("LD_FD101")) {
                        result.put("stato", "Conservato");
                    }
                    result.put("id", entity.getId());
                } catch (Exception e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(ex));
                    logger.error(ExceptionUtils.getStackTrace(ex));
                    result.put("globalError", true);
                    result.put("errorMessage", "Errore nell'invio in conservazione");
                }
            } else {
                result.put("globalError", true);
                result.put("errorMessage", "Errore nell'invio in conservazione");

                logger.error("error: {}", ExceptionUtils.getMessage(ex));
                logger.error(ExceptionUtils.getStackTrace(ex));
                try {
                    result.put("id", entity.getId());
                    result.put("stato", "In Errore");
                } catch (Exception e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(ex));
                    logger.error(ExceptionUtils.getStackTrace(ex));
                }
            }
            //throw new RuntimeException("Errore nell'invio in conservazione");
        } finally {
            if (loginToken != null) {
                doLogout(WSDL_URL, loginToken);
            }
            ws.setSkipPolicies(wasSkippingPolicies);
        }
        result.put("id", entity.getId());
        return result;
    }
}
