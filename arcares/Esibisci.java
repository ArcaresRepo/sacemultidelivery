package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.definitions.EntityTypeDefinition;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.io.ByteArrayInputStream;
import java.io.File;
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

import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.util.Calendar;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpResponse;

public class Esibisci implements WrRuleClassBody {

    WorkSession ws;
    Entity entity;
    Logger logger;
    Entity option;
    private String WSDL_URL;
    private String RETRIEVE_URL;

    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    private final int GET = 1, POST = 2, PUT = 3, DELETE = 4;
    String loginToken = null;
    private String POLICY_ID;
    private String DOCUMENT_CLASS;
    private String DOCCLASS_LABEL;
    private String PATH_CONSERVAZIONE;
    private String WEB_SERVICE_URL;
    private String tmpFileName;

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
            //baseRequest.addHeader(â€œAcceptâ€�, â€œmultipart/mixedâ€�);
            DefaultHttpClient httpclient = new DefaultHttpClient();
            return httpclient.execute(baseRequest);
        } catch (IOException e) { //gestire eccezione}
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
        }
        return hashString;
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

    private File retrieve(String url, String sessionId) throws Exception {

        logger.debug("Call to retrieve ... " + url);
        HttpResponse response = callWS(url, GET, "application/x-zip-compressed", null,
                null, sessionId);
        if (response.getStatusLine().getStatusCode() == 200) {
            logger.info("Documento estratto.");
        } else {
            logger.info("Result code: {}", response.getStatusLine().getStatusCode());
            if (response.getStatusLine().getStatusCode() >= 300) {
                HttpEntity resEntity = response.getEntity();
                String result = EntityUtils.toString(resEntity);
                logger.info("Result: " + result);
                Document xmlDoc = getDocumentXML(result);
        
                logger.warn("Retrieve fallito.");
                throw new Exception(getErrorFromXML(xmlDoc));
            }
            String errorMessage = "Documento non estratto";
            logger.info(errorMessage);
            throw new Exception(errorMessage);
        }

        InputStream is = response.getEntity().getContent();
        File zip = File.createTempFile(tmpFileName, "zip");
        FileOutputStream fos = new FileOutputStream(zip);
        BufferedOutputStream bos = new BufferedOutputStream(fos);

        int read = -1;

        while ((read = is.read()) != -1) {
            bos.write(read);
        }

        bos.flush();
        bos.close();
        is.close();
        return zip;

    }

    public Object run(Map parameters) {
        byte[] retData = null;
        setParameters(parameters);

        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        File outputFile = null;
        try {
            ws.setSkipPolicies(true);

            EntityTypeDefinition entityType = entity.getEntityTypeDefinition();
            String token = (String) entity.getPropertyValue("token");
            String query = "//element(*, opzioniConservazione)[@entityType = '" + entityType.getId() + "']";
            if (token == null || token.isEmpty()) {
                logger.error("Token non valorizzato");
                return null;
            }
            List optionList = ws.getQueryManager().executeQuery(query).getResults();

            if (optionList.size() == 1) {
                option = (Entity) optionList.get(0);
            } else {
                logger.error("Entity opzioni non reperibile");
                return null;
            }

            String username = (String) option.getPropertyValue("username");
            String userpwd = (String) option.getPropertyValue("userpwd");
            String bucket = (String) option.getPropertyValue("bucket");
            DOCUMENT_CLASS = (String) option.getPropertyValue("documentClass");
            DOCCLASS_LABEL = (String) option.getPropertyValue("docclassLabel");
            POLICY_ID = (String) option.getPropertyValue("policyId");
            WEB_SERVICE_URL = (String) option.getPropertyValue("webServiceURL");

            logger.info("Credenziali: {}/{} [{}}", username, userpwd, bucket);
            logger.info("Document class: {} -> {} [{}]", DOCUMENT_CLASS, DOCCLASS_LABEL);
            logger.info("Policy: {} ", POLICY_ID);
            
            WSDL_URL = WEB_SERVICE_URL + "/ws/session";
            RETRIEVE_URL = WEB_SERVICE_URL + "/ws/" + bucket + "/document/" + token;
            logger.info("Percorso di esibilizione: {}", RETRIEVE_URL);

            logger.debug("Loop sulle chiavi della mappa...");

            loginToken = executeLogin(WSDL_URL, username, userpwd);
            Calendar calendar = Calendar.getInstance();
            tmpFileName = "" + calendar.getTimeInMillis();

            outputFile = retrieve(RETRIEVE_URL, loginToken);
            retData = Files.readAllBytes(outputFile.toPath());
            /*
            File cacheFile = ResourcesCache.getInstance().createCachedFile(tmpFileName, ".zip");
            FileUtils.copyFile(outputFile, cacheFile);
            logger.info("outputFile: {}", outputFile.getAbsolutePath());
            logger.info("cacheFile: {}", cacheFile.getAbsolutePath());
            result.put("cacheFile", cacheFile.getName());*/
            
        } catch (Exception ex) {
            
            logger.error("error: {}", ExceptionUtils.getMessage(ex));
            logger.error(ExceptionUtils.getStackTrace(ex));

            //throw new RuntimeException("Errore nell'invio in conservazione");
        } finally {
            if (loginToken != null) {
                doLogout(WSDL_URL, loginToken);
            }
            if (outputFile != null) {
                try {
                    outputFile.delete();
                } catch (Exception ex) {
                    logger.error("error: {}", ExceptionUtils.getMessage(ex));
                }
            }
            ws.setSkipPolicies(wasSkippingPolicies);
        }
        
        return retData;
    }
}
