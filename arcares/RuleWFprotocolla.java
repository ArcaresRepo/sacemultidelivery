package rules.arcares;
 
import com.google.zxing.BarcodeFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
 
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
 
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
 
import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.StringBody;
import org.w3c.dom.Element;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.Writer;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Image;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfImage;
import com.itextpdf.text.pdf.PdfIndirectObject;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.itextpdf.text.pdf.parser.SimpleTextExtractionStrategy;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import java.util.Hashtable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
 
public class RuleWFprotocolla implements WrRuleClassBody {
 
    WorkSession ws;
    Entity entity;
    Logger logger;
 
    Entity option;
 
    Connection con = null;
    private Statement updateStatemant;
 
    private final int GET = 1, POST = 2, PUT = 3, DELETE = 4;
 
    String url = null;
    String loginToken = null;
 
    String numeroProtocollo = "";
    String dataProtocollo = "";
    String annoProtocollo = "";
    Calendar cProtocollo;
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    private final String classeDocumentale = "Documentazione prodotta da K4F|10016";
 
    private final String tipoProtocollo = "wr:docInUscita";
 
    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }
 
    HttpResponse callWS(String url, int wsMethods, String mediaType, String acceptType, HttpEntity entity) {
        try {
            HttpRequestBase baseRequest = null;
            switch (wsMethods) {
                case GET:
                    logger.info("new HttpGet({})", url);
                    baseRequest = new HttpGet(url);
                    break;
 
                case POST:
                    baseRequest = new HttpPost(url);
                    logger.info("new HttpPost({})", url);
                    if (entity != null) {
                        ((HttpPost) baseRequest).setEntity(entity);
                    }
                    break;
 
                case PUT:
                    baseRequest = new HttpPut(url);
                    logger.info("new HttpPut({})", url);
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
            //da settare per l'esibizione multipart
            //baseRequest.addHeader(“Accept”, “multipart/mixed”);
            DefaultHttpClient httpclient = new DefaultHttpClient();
            return httpclient.execute(baseRequest);
        } catch (IOException e) { //gestire eccezione}
            logger.error(ExceptionUtils.getStackTrace(e));
            logger.error("Eccezione durante l'esecuzione della richiesta", e);
            return null;
        } catch (Exception e) { //gestire eccezione}
            logger.error(ExceptionUtils.getStackTrace(e));
            logger.error("Eccezione durante l'esecuzione della richiesta", e);
            return null;
        }
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
 
    // esegui il login su protocollo
    private String executeLogin(String url) throws Exception {
        logger.info("Eseguo il login a legalDoc [{}]...", url);
 
        HttpResponse response = callWS(url, GET, null,
                null, null);
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
 
        NodeList list = xmlDoc.getElementsByTagName("response");
        Node node = list.item(0).getFirstChild();
        String textResult = node.getTextContent();
        logger.info("Login token: {}", textResult);
        return textResult;
    }
 
    private void doLogout(String resourceUrl) {
        HttpResponse response = callWS(resourceUrl, DELETE,
                null, null, null);
        logger.info("Logout response: " + response.toString());
    }
 
    private StringBuilder createFieldsProtocollo(String idMittente,
            String idGruppo,
            String corrispondentePrinc,
            String oggetto) throws Exception {
        logger.info("corrispondentePrinc {}", corrispondentePrinc);
        logger.info("oggetto {}", oggetto);
 
        StringBuilder indexTxt = new StringBuilder();
        indexTxt.append("<?xml version='1.0' encoding='utf-8'?>");
        indexTxt.append("<fields>");
 
        indexTxt.append("<field name='wrprop:mittente'>");
        indexTxt.append("<value>").append(idMittente).append("</value>");
        indexTxt.append("</field>");
 
        indexTxt.append("<field name='wrprop:mittenti'>");
        indexTxt.append("<value>").append(idMittente).append("</value>");
        indexTxt.append("</field>");
 
        indexTxt.append("<field name='wrprop:group'>");
        indexTxt.append("<value>").append(idGruppo).append("</value>");
        indexTxt.append("</field>");
 
        indexTxt.append("<field name='wrprop:destinatari'>");
        //indexTxt.append("<field name='wrprop:corrispondenti'>");
        if (corrispondentePrinc != null && !corrispondentePrinc.isEmpty()) {
            corrispondentePrinc = StringEscapeUtils.escapeXml(corrispondentePrinc);
            indexTxt.append("<value>").append(corrispondentePrinc).append("</value>");
        }
        indexTxt.append("</field>");
 
        oggetto = StringEscapeUtils.escapeXml(oggetto);
 
        //indexTxt.append("<field name='wrprop:oggetto'>");
        indexTxt.append("<field name='wrprop:name'>");
        if (oggetto != null && !oggetto.isEmpty()) {
            oggetto = StringEscapeUtils.escapeXml(oggetto);
            indexTxt.append("<value>").append(oggetto).append("</value>");
        }
        indexTxt.append("</field>");
 
        indexTxt.append("<field name='wrprop:tipo'>");
        indexTxt.append("<value>").append(classeDocumentale).append("</value>");
        indexTxt.append("</field>");
        /*
        indexTxt.append("<field name='wrprop:spedizione'>");
        indexTxt.append("<value>informatico</value>");
        indexTxt.append("</field>");
         */
        indexTxt.append("</fields>");
        logger.info("indexTxt {}", indexTxt);
        return indexTxt;
    }
 
    private String insertEntity(String url, String token, String entityType, String parentId, StringBuilder fields) throws Exception {
        logger.info("Eseguo l'insert a [{}]...", url);
 
        MultipartEntity multipartEntity = new MultipartEntity();
        multipartEntity.addPart("token", new StringBody(token));
        multipartEntity.addPart("parentId", new StringBody(parentId));
        multipartEntity.addPart("entityType", new StringBody(entityType));
        multipartEntity.addPart("metaData", new StringBody(fields.toString()));
 
        logger.info("callWS...");
        HttpResponse response = callWS(url, POST, null,
                null, multipartEntity);
        HttpEntity resEntity = response.getEntity();
        String result = EntityUtils.toString(resEntity);
        logger.info("Result: " + result);
        if (result == null || result.isEmpty()) {
            logger.error("Protocollazione fallita");
            throw new Exception("Protocollazione fallita");
        }
 
        Document xmlDoc = getDocumentXML(result);
        logger.info("Result code: {}", response.getStatusLine().getStatusCode());
        if (response.getStatusLine().getStatusCode() >= 300) {
            logger.error("Protocollazione fallita");
            throw new Exception(getErrorFromXML(xmlDoc));
        }
        logger.info("Protocollazione eseguita con successo.");
 
        NodeList list = xmlDoc.getElementsByTagName("entity");
        Node nodeEntity = list.item(0);
        Element e = (Element) nodeEntity;
        logger.info("Element {}", e.toString());
        String idEntity = e.getAttribute("id");
        return idEntity;
    }
 
    private String getEntity(String url) throws Exception {
        logger.info("Eseguo la get a [{}]...", url);
 
        logger.info("callWS...");
        HttpResponse response = callWS(url, GET, null,
                null, null);
        HttpEntity resEntity = response.getEntity();
        String result = EntityUtils.toString(resEntity);
        logger.info("Result: " + result);
        if (result == null || result.isEmpty()) {
            logger.error("Recupero fallito");
            throw new Exception("Recupero fallito");
        }
 
        Document xmlDoc = getDocumentXML(result);
        logger.info("Result code: {}", response.getStatusLine().getStatusCode());
        if (response.getStatusLine().getStatusCode() >= 300) {
            logger.error("Recupero fallito");
            throw new Exception(getErrorFromXML(xmlDoc));
        }
 
        NodeList list = xmlDoc.getElementsByTagName("entity");
        Node nodeEntity = list.item(0);
        Element e = (Element) nodeEntity;
        logger.info("Element {}", e.toString());
        String idEntity = e.getAttribute("idEntity");
 
        XPathFactory xPathfactory = XPathFactory.newInstance();
        XPath xpath = xPathfactory.newXPath();
        XPathExpression expr = xpath.compile("//field[@name=\"wrprop:numProt\"]");
        NodeList nodeListNP = (NodeList) expr.evaluate(xmlDoc, XPathConstants.NODESET);
        Node nodeNP = nodeListNP.item(0);
        numeroProtocollo = nodeNP.getFirstChild().getTextContent();
        logger.info("Numero Protocollo {}", numeroProtocollo);
 
        xPathfactory = XPathFactory.newInstance();
        xpath = xPathfactory.newXPath();
        expr = xpath.compile("//field[@name=\"wrprop:dateProt\"]");
        nodeListNP = (NodeList) expr.evaluate(xmlDoc, XPathConstants.NODESET);
        nodeNP = nodeListNP.item(0);
        dataProtocollo = nodeNP.getFirstChild().getTextContent();
        logger.info("Data Protocollo {}", dataProtocollo);
 
        return idEntity;
    }
 
    public Object run(Map parameters) {
        HashMap retMap = new HashMap();
        setParameters(parameters);
        logger.info("RuleWFprotocolla: Start");
 
        String idFlusso = null;
        String idDocumento = null;
 
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        try {
            ws.setSkipPolicies(true);
            con = getConnectionForArcaresDB();
 
            idFlusso = (String) entity.getPropertyValue("idFlusso");
            idDocumento = (String) entity.getPropertyValue("idDocumento");
            String path = (String) entity.getPropertyValue("docPath");
 
            Boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
            Boolean flagFirma = (Boolean) entity.getPropertyValue("flagFirma");
            Boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
 
            String optProt = "/jcr:root//element(*,opzioniProtocollo)";
            QueryManager qm = ws.getQueryManager();
            List<Entity> optionList = qm.executeQuery(optProt).getResults();
 
            if (optionList.size() == 1) {
                option = (Entity) optionList.get(0);
            } else {
                throw new Exception("Errore nel recupero entita opzioniProtocollo");
            }
 
            url = (String) option.getPropertyValue("url");
            String username = (String) option.getPropertyValue("user");
            String userpwd = (String) option.getPropertyValue("password");
            String idMittente = (String) option.getPropertyValue("idMittente");
            String idCartella = (String) option.getPropertyValue("idCartella");
            String idGruppo = (String) option.getPropertyValue("idGruppo");
 
            //String tipoProtocollo = "wr:protocolloInUscita";
            logger.info("RuleWFprocotolla - Credenziali: {}/{} [{}}", username, userpwd);
 
            loginToken = executeLogin(url + "/login?username=" + username + "&password=" + userpwd);
 
            logger.info("URL: {}", url);
            logger.info("loginToken: {}", loginToken);
 
            String select = "SELECT * FROM WRDC_KEYS "
                    + "WHERE ID_DOCUMENTO='" + idDocumento + "' ORDER BY ID_KEY";
            logger.info("RuleWFprotocolla: Recupera Keys Documento {}", idDocumento);
 
            updateStatemant = con.createStatement();
            ResultSet res = updateStatemant.executeQuery(select);
            String corrispondentePrinc = null;
            while (res.next()) {
                String idKey = res.getString("ID_KEY").trim();
                logger.info("idKey: {}", idKey);
                logger.info("value: {}", res.getString("DES_VALUE_KEY"));
 
                if (idKey.equals("001")) {
                    corrispondentePrinc = res.getString("DES_VALUE_KEY");
                } else if (idKey.equals("004")) {
                    corrispondentePrinc += " - " + res.getString("DES_VALUE_KEY");
                }
            }
            res.close();
            String oggetto = (String) entity.getContainerEntity().getPropertyValue("desTipoDoc");
 
            StringBuilder fields = createFieldsProtocollo(idMittente,
                    idGruppo,
                    corrispondentePrinc,
                    oggetto);
            String statoProtocolla = "2";
 
            String entityId = insertEntity(url + "/entity/insert", loginToken, tipoProtocollo, idCartella, fields);
 
            logger.info("RuleWFprocotolla: entityId: {} ", entityId);
            getEntity(url + "/entity/" + entityId + "?token=" + loginToken);
            if (numeroProtocollo == null || numeroProtocollo.isEmpty()) {
                flagFirma = false;
                statoProtocolla = "3";
                flagConserva = false;
                flagInviaMail = false;
            }
 
            if (dataProtocollo != null && !dataProtocollo.isEmpty()) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy");
                Date date = dateFormat.parse(dataProtocollo);
                cProtocollo = Calendar.getInstance();
                cProtocollo.setTime(date);
                annoProtocollo = "" + cProtocollo.get(Calendar.YEAR);
            }
 
            //aggiorno lo stato "Firmato Correttamente"
            String update = "UPDATE WRDC_DETAIL "
                    + "SET COD_PROTOCOL = '" + numeroProtocollo + "/" + annoProtocollo + "', COD_STATO_PROT = '" + statoProtocolla + "', COD_ERRORE_PROT = '', DES_ERRORE_PROT = '' "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
 
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", ws);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", update);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
 
            entity.setProperty("statoProtocolla", statoProtocolla);
            entity.setProperty("idProtocollo", entityId);
            entity.setProperty("nProtocollo", numeroProtocollo);
            entity.setProperty("dataProtocollo", cProtocollo);
            entity.setProperty("tipoProtocollo", tipoProtocollo);
 
            createQRCode(numeroProtocollo, annoProtocollo, path);
 
            entity.persist();
            ws.save();
 
            logger.info("RuleWFprocotolla: Documento protocollato correttamente");
 
            retMap.put("conserva", flagConserva);
            retMap.put("firma", flagFirma);
            retMap.put("invio", flagInviaMail);
 
        } catch (Exception ex) {
            String updateError = "UPDATE WRDC_DETAIL "
                    + "SET  COD_STATO = '4', COD_STATO_PROT = '3', DES_ERRORE_PROT = 'Errore Tecnico Protocollazione' "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
 
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", ws);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", updateError);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
 
            entity.setProperty("stato", "4");
            entity.setProperty("statoProtocolla", "3");
            entity.persist();
 
            try {
                ws.save();
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.debug(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFprotocolla (ATTENZIONE STATO DEL PROTOCOLLO NON AGGIORNATO SU WR!!!!): " + e.getMessage(), e);
            }
            logger.error("error: {}", ExceptionUtils.getMessage(ex));
            logger.error(ExceptionUtils.getStackTrace(ex));
 
            throw new RuntimeException("ERRORE in RuleWFprotocolla errore nella protocollazione");
 
            //GESTIRE L'ECCEZIONE
        } finally {
            if (loginToken != null) {
                doLogout(url + "/login/" + loginToken);
            }
 
            if (con != null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    logger.error("RuleWFprotocolla Database Connection Error: {}", ExceptionUtils.getMessage(e));
                }
            }
            ws.setSkipPolicies(wasSkippingPolicies);
        }
        logger.info("RuleWFprocotolla: End");
 
        return retMap;
    }
 
    private Connection getConnectionForArcaresDB() {
        // recupera opzioni di collegamento al DB di Arcares
        String query = "/jcr:root//element(*,opzioneArcares)";
        QueryManager qm = ws.getQueryManager();
        List<Entity> list = qm.executeQuery(query).getResults();
        Entity optArcares = (Entity) list.get(0);
 
        // connessione al db
        Connection con = null;
        try {
            Class.forName((String) optArcares.getPropertyValue("jdbcDriver"));
            con = DriverManager.getConnection((String) optArcares.getPropertyValue("dataSource"),
                    (String) optArcares.getPropertyValue("nomeUtente"),
                    (String) optArcares.getPropertyValue("password"));
//        } catch (ModelException | SQLException | ClassNotFoundException e) {
        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE IN RuleWFinviaMail: " + e.getMessage(), e);
        }
        return con;
    }
 
    private void createQRImage(File qrFile, String format, String qrCodeText) throws Exception {
        // get a byte matrix for the data
        BitMatrix matrix = null;
        int h = 50; //30;
        int w = 50; //150;
        Writer writer = new MultiFormatWriter();
        try {
            Hashtable<EncodeHintType, String> hints = new Hashtable<EncodeHintType, String>(2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            matrix = writer.encode(qrCodeText, BarcodeFormat.QR_CODE, w, h, hints);
        } catch (com.google.zxing.WriterException e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
        }
 
        // change this path to match yours (this is my mac home folder, you can use: c:\\qr_png.png if you are on windows)
        try {
            MatrixToImageWriter.writeToFile(matrix, format, qrFile);
            logger.info("printing to " + qrFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
        }
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

    private void createQRCode(String numeroProtocollo, String annoProtocollo, String path) throws Exception {
        File workDir = getWorkingDir();
        List resourceList = entity.getStructuredProperty("resources").getRecords();
        for (int j = 0; j < resourceList.size(); j++) {
            StructuredPropertyRecord record = (StructuredPropertyRecord) resourceList.get(j);
            String curDocFileName = (String) record.getPropertyValue("originalFilename");
            File tmpPngFile = new File(workDir, curDocFileName + ".png");

            createQRImage(tmpPngFile, "png", annoProtocollo + numeroProtocollo);

            InputStream inputStream = (InputStream) record.getPropertyValue("data");
            File templateFile = new File(workDir, "old_" + curDocFileName);
            OutputStream outputStream = new FileOutputStream(templateFile);
            IOUtils.copy(inputStream, outputStream);
            File templateFileDest = new File(workDir, curDocFileName);

            //logger.info("outputStream: {}", outputStream);

            manipulatePdf(templateFile.getAbsolutePath(), templateFileDest.getAbsolutePath(), tmpPngFile.getAbsolutePath(),  annoProtocollo,  numeroProtocollo);

            if (path != null && !path.isEmpty()) {
                File docArcares = new File(path);
                try
                {
                     Files.copy(templateFileDest.toPath(), docArcares.toPath(), REPLACE_EXISTING);
                } catch(Exception ex)
                {
                    // In caso di errore di copia perde l'originale quindi lo salviamo in un path 
                    File docArcaresSaved = new File(path+".saved.pdf");
                    Files.copy(templateFileDest.toPath(), docArcaresSaved.toPath());
                    logger.error("error copy file to Arcares repository saved original in :saved.pdf: {}", ExceptionUtils.getMessage(ex));
                    throw ex;
                }
            }

            logger.info("templateFile: {}", templateFileDest.getAbsolutePath());

            record.setProperty("data", new FileInputStream(templateFileDest));
            record.setProperty("fileSize", FileUtils.sizeOf(templateFileDest));
            try {
                templateFile.delete();
                templateFileDest.delete();
                tmpPngFile.delete();
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.error(ExceptionUtils.getStackTrace(e));
            }

        }
        entity.persist();
        ws.save();
    }
	
    private File getWorkingDir() {
        Calendar ora = Calendar.getInstance();
        File temporaryDir = new File(System.getProperty("java.io.tmpdir"),
                "" + ora.getTimeInMillis());
        logger.info("Temporary working dir: {}", temporaryDir.getPath());
        temporaryDir.mkdirs();
        return temporaryDir;
    }
 
        public void manipulatePdf(String src, String dest, String img, String annoProtocollo, String numeroProtocollo) throws IOException, DocumentException {
        int pagina = 0;
        
        PdfReader reader = new PdfReader(src);
        SimpleTextExtractionStrategy strategy = new SimpleTextExtractionStrategy();
           for (int i = 2; i < reader.getNumberOfPages(); i++) {

            String currentText = PdfTextExtractor.getTextFromPage(reader, i, strategy);
            if (currentText.contains("Pag. 1/")) {
                pagina = i;
                break;
                
            }  
            logger.info("Pagina lettera cessione: {}", i);
        }
        PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(dest));
        Image image = Image.getInstance(img);
        PdfImage stream = new PdfImage(image, "", null);
        //stream.put(new PdfName("ITXT_SpecialId"), new PdfName("123456789"));
        PdfIndirectObject ref = stamper.getWriter().addToBody(stream);
        image.setDirectReference(ref.getIndirectReference());
        float startY = 710; //reader.getPageSizeWithRotation(1).getTop() - 45;
        float startX = 300;
        image.setAbsolutePosition(startX, startY);
        PdfContentByte over = stamper.getOverContent(1);
        over.addImage(image);
        ColumnText ct = new ColumnText(over);
        ct.setSimpleColumn(725,725,315,315);
        ct.setText(new Phrase(new Chunk("Prot. nr: " + annoProtocollo +"/"+numeroProtocollo, FontFactory.getFont(FontFactory.HELVETICA, 9))));
        //ct.setText(new Phrase(annoProtocollo +"/"+numeroProtocollo));
        ct.go();
        if(pagina != 0)
        {
            startY = 725; //reader.getPageSizeWithRotation(1).getTop();
            startX = 320;
            image.setAbsolutePosition(startX, startY);
            over = stamper.getOverContent(pagina);
            over.addImage(image);
            ColumnText ct1 = new ColumnText(over);
            ct1.setSimpleColumn(740,740,335,315);
            ct1.setText(new Phrase(new Chunk("Prot. nr: " + annoProtocollo +"/"+numeroProtocollo, FontFactory.getFont(FontFactory.HELVETICA, 9))));
            ct1.go();
           
            
        }
        stamper.close();
        reader.close();
    }

}