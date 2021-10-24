// Forked from lsFusion Platform
// lsfusion.server.physics.dev.integration.external.to.mail.EmailReceiver
// https://github.com/lsfusion/platform
// server/src/main/java/lsfusion/server/physics/dev/integration/external/to/mail/EmailReceiver.java

package lsfusion.solutions.observer;

import com.sun.mail.imap.IMAPBodyPart;
import com.sun.mail.imap.IMAPInputStream;
import lsfusion.base.BaseUtils;
import lsfusion.base.ExceptionUtils;
import lsfusion.base.file.FileData;
import lsfusion.base.file.IOUtils;
import lsfusion.base.file.RawFileData;
import lsfusion.server.data.sql.exception.SQLHandledException;
import lsfusion.server.data.value.DataObject;
import lsfusion.server.language.ScriptingErrorLog;
import lsfusion.server.language.ScriptingLogicsModule;
import lsfusion.server.logics.action.controller.context.ExecutionContext;
import lsfusion.server.logics.classes.ValueClass;
import lsfusion.server.logics.classes.data.time.DateTimeConverter;
import lsfusion.server.logics.classes.user.ConcreteCustomClass;
import lsfusion.server.logics.property.classes.ClassPropertyInterface;
import lsfusion.server.physics.admin.log.ServerLoggers;
import lsfusion.server.physics.dev.integration.internal.to.InternalAction;
import org.apache.http.entity.ContentType;
import org.apache.poi.hmef.Attachment;
import org.apache.poi.hmef.HMEFMessage;

import java.io.*;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.*;
import javax.mail.internet.*;

public class HeaderReader extends InternalAction {

    // Used to transfer parameters from the LSFusion module
    private final ClassPropertyInterface itemailInterface;

    // Date format for the From_ line timestamp
    private static final SimpleDateFormat simpleDateFormat =
            new SimpleDateFormat("EEE MMM dd HH:mm:ss ZZZZ yyyy", Locale.ENGLISH);

    public HeaderReader(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        itemailInterface = i.next();
    }

    @Override
    protected void executeInternal(ExecutionContext<ClassPropertyInterface> context)
            throws SQLException, SQLHandledException {
        try {
            DataObject commandArgs = context.getDataKeyValue(itemailInterface);
            this.readHeaders(context, commandArgs);
        }
        catch (Exception e) {
            throw ExceptionUtils.propagate(e, SQLException.class, SQLHandledException.class);
        }
    }

    private LocalDateTime getReceivedDate(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
            throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        String received = (String) LM.findProperty("timestamp[Itemail]").
                readClasses(context, commandArgs).getValue();

        if (received != null) {
            try {
                synchronized(simpleDateFormat) {
                    Date date = simpleDateFormat.parse(received);
                    Timestamp receivedTimestamp = date == null ? null : new Timestamp(date.getTime());
                    return DateTimeConverter.sqlTimestampToLocalDateTime(receivedTimestamp);
                }
            } catch (ParseException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public String getRecipientsString(MimeMessage data)
            throws MessagingException {
        Address[] addressRecipients = data.getAllRecipients();
        String[] allRecipients = new String[addressRecipients.length];

        for (int i=0; i<addressRecipients.length; i++)
        {
            allRecipients[i] = ((InternetAddress)addressRecipients[i]).getAddress();
        }

        return String.join(", ", allRecipients);
    }

    private String decodeFileName(String value) {
        try {
            Pattern p = Pattern.compile("\\=\\?[^?]*\\?\\w\\?[^?]*\\?\\=");

            for(Matcher m = p.matcher(value);
                m.find();
                value = value.replace(m.group(), MimeUtility.decodeText(m.group()))) {
                // This empty body checks if there is an exception possible
            }

            value = MimeUtility.decodeText(value);

        } catch (UnsupportedEncodingException e) {
            value = "attachment.txt";
        }

        return value;
    }

    private HeaderReader.MultipartBody extractWinMail(File winMailFile)
            throws IOException {
        HMEFMessage msg = new HMEFMessage(new FileInputStream(winMailFile));
        Map<String, FileData> attachments = new HashMap();
        Iterator var5 = msg.getAttachments().iterator();

        while(var5.hasNext()) {
            Attachment attach = (Attachment)var5.next();
            String attachName = attach.getFilename();
            attachments.put(attachName,
                    new FileData(new RawFileData(attach.getContents()), BaseUtils.getFileExtension(attachName)));
        }

        return new HeaderReader.MultipartBody(msg.getBody(), attachments);
    }

    private Object getBodyPartContent(BodyPart bp)
            throws MessagingException, IOException {
        Object content = null;
        if (bp instanceof IMAPBodyPart) {
            String encoding = ((IMAPBodyPart)bp).getEncoding();
            if (encoding != null) {
                Object plainContent = null;

                try {
                    plainContent = bp.getContent();
                } catch (Exception var5) {
                }

                content = plainContent instanceof String ? plainContent :
                        MimeUtility.decode(bp.getInputStream(), encoding);
            }
        }

        return content != null ? content : bp.getContent();
    }

    private String parseIMAPInputStream(BodyPart bp, IMAPInputStream content)
            throws IOException, MessagingException {
        String contentType = bp.getContentType();
        if (contentType != null) {
            org.apache.http.entity.ContentType ct = null;

            try {
                ct = ContentType.parse(contentType);
            } catch (Exception var7) {
            }

            if (ct != null) {
                String mimeType = ct.getMimeType();
                if (mimeType.equals("text/plain")) {
                    byte[] bytes = IOUtils.readBytesFromStream(content);
                    return new String(bytes, ct.getCharset());
                }
            }
        }

        return null;
    }

    // Without archive unpacking
    private Map<String, FileData> unpack(RawFileData byteArray, String fileName) {
        String[] fileNameAndExt = fileName.split("\\.");
        String fileExtension = fileNameAndExt.length > 1 ? fileNameAndExt[fileNameAndExt.length - 1].trim() : "";

        Map<String, FileData> attachments = new HashMap();
        attachments.put(fileName, new FileData(byteArray, fileExtension));

        return attachments;
    }

    private HeaderReader.MultipartBody getMultipartBodyStream(
            String subjectEmail, FilterInputStream filterInputStream, String fileName)
            throws IOException {
        RawFileData byteArray = new RawFileData(filterInputStream);
        Map<String, FileData> attachments = new HashMap();
        attachments.putAll(this.unpack(byteArray, fileName));
        return new HeaderReader.MultipartBody(subjectEmail, attachments);
    }

    private HeaderReader.MultipartBody getMultipartBody(String subjectEmail, Multipart mp)
            throws IOException, MessagingException {
        String body = "";
        Map<String, FileData> attachments = new HashMap();

        for(int i = 0; i < mp.getCount(); ++i) {
            // In case of mbox archive tends to be MimeBodyPart, which extends BodyPart and implements MimePart
            BodyPart bp = mp.getBodyPart(i);
            String disp = bp.getDisposition();
            if (disp != null && disp.equalsIgnoreCase("attachment")) {
                String fileName = this.decodeFileName(bp.getFileName());
                InputStream is = bp.getInputStream();
                File f = File.createTempFile("attachment", "");

                try {
                    FileOutputStream fos = new FileOutputStream(f);
                    byte[] buf = new byte[4096];

                    int bytesRead;
                    while((bytesRead = is.read(buf)) != -1) {
                        fos.write(buf, 0, bytesRead);
                    }

                    fos.close();
                    if (bp.getContentType() != null && bp.getContentType().contains("application/ms-tnef")) {
                        attachments.putAll(this.extractWinMail(f).attachments);
                    } else {
                        attachments.putAll(this.unpack(new RawFileData(f), fileName));
                    }
                } catch (IOException var20) {
                    ServerLoggers.mailLogger.error(
                            "Error reading attachment '" + fileName + "' from email '" + subjectEmail + "'");
                    throw var20;
                } finally {
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }

                }
            } else {
                try {
                    Object content = this.getBodyPartContent(bp);
                    if (content instanceof FilterInputStream) {
                        RawFileData byteArray = new RawFileData((FilterInputStream)content);
                        String fileName = this.decodeFileName(bp.getFileName());
                        attachments.putAll(this.unpack(byteArray, fileName));
                    } else if (content instanceof MimeMultipart) {
                        // The way for the mbox archive
                        body = this.getMultipartBody(subjectEmail, (Multipart)content).message;
                    } else if (content instanceof IMAPInputStream) {
                        body = this.parseIMAPInputStream(bp, (IMAPInputStream)content);
                    } else {
                        body = String.valueOf(content);
                    }
                } catch (IOException var19) {
                    throw new RuntimeException("Email subject: " + subjectEmail, var19);
                }
            }
        }

        return new HeaderReader.MultipartBody(body, attachments);
    }

    private HeaderReader.MultipartBody getMimeMessageContent(MimeMessage data)
            throws IOException, MessagingException {
        // Possible types of the content object:
        // Object, meaning getContent from DataHandler
        // abstract Message implements Part
        // abstract Multipart
        // MimeMultipart extends Multipart
        Object content = data.getContent();
        HeaderReader.MultipartBody messageEmail;

        // Checking "instanceof" rather than value using a switch statement
        // https://softwareengineering.stackexchange.com/a/271877
        if (content instanceof Multipart) {
            // In case of mbox archive this selection is going to be MimeMultipart, which extends Multipart
            messageEmail = this.getMultipartBody(
                    data.getSubject(), (Multipart)content);
        } else if (content instanceof FilterInputStream) {
            messageEmail = this.getMultipartBodyStream(
                    data.getSubject(), (FilterInputStream)content, this.decodeFileName(data.getFileName()));
        } else if (content instanceof String) {
            // Mask the default value for type char
            messageEmail = new HeaderReader.MultipartBody(
                    ((String)content).replace("\u0000", ""), (Map)null);
        } else {
            messageEmail = null;
        }

        if (messageEmail == null) {
            messageEmail = new HeaderReader.MultipartBody(
                    content == null ? null : String.valueOf(content), (Map)null);
            ServerLoggers.mailLogger.error(
                    "Warning: missing attachment '" + content + "' from email '" + data.getSubject() + "'");
        }

        return messageEmail;
    }

    private boolean importEmail(ExecutionContext<ClassPropertyInterface> context,
                               DataObject commandArgs, MimeMessage data)
            throws IOException, ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        // Create new object and store it in the database within a separate session
        try (ExecutionContext.NewSession<ClassPropertyInterface> emailContext = context.newSession()) {
            DataObject emailObject = emailContext.addObject((ConcreteCustomClass) LM.findClass("Email"));

            // Create new Email object
            LM.findProperty("itemail[Email]").change(commandArgs, emailContext, emailObject);

            // Necessary link to Account object
            LM.findProperty("account[Email]").change(
                    LM.findProperty("account[Itemail]").readClasses(emailContext, commandArgs).getValue(),
                    emailContext, emailObject);

            // Internal unique id of the Email object
            String idEmail = (String) LM.findProperty("internalID[Itemail]").
                    readClasses(context, commandArgs).getValue();
            LM.findProperty("id[Email]").change(idEmail, emailContext, emailObject);

            // Value from the header "Date: "
            Date sentDate = data.getSentDate();
            Timestamp sentTimestamp = sentDate == null ? null : new Timestamp(sentDate.getTime());
            LM.findProperty("dateTimeSent[Email]").change(
                    DateTimeConverter.sqlTimestampToLocalDateTime(sentTimestamp), emailContext, emailObject);

            // Set the receive date from the From_ line timestamp
            LM.findProperty("dateTimeReceived[Email]").change(
                    this.getReceivedDate(emailContext, commandArgs), emailContext, emailObject);

            // From address is a sigle email, we populate also a property, which longer than 100 symbols
            LM.findProperty("from[Email]").change(((InternetAddress)data.getFrom()[0]).getAddress(),
                    emailContext, emailObject);
            LM.findProperty("fromAddress[Email]").change(
                    ((InternetAddress)data.getFrom()[0]).getAddress(), emailContext, emailObject);

            // To addresses may be many, we populate also a property, which longer than 100 symbols
            String recipients = this.getRecipientsString(data);
            LM.findProperty("to[Email]").change(recipients, emailContext, emailObject);
            LM.findProperty("toAddress[Email]").change(recipients, emailContext, emailObject); //

            // Value from the header "Subject: "
            LM.findProperty("subject[Email]").change(data.getSubject(), emailContext, emailObject);

            // Output from the textual content parsing
            HeaderReader.MultipartBody messageContent = this.getMimeMessageContent(data);
            LM.findProperty("message[Email]").change(messageContent.message, emailContext, emailObject);

            // The parsed attachments from the MultipartBody are being dropped in order to keep the database smaller
            // once needed, the functionality may be forked from the EmailReceiver.importAttachments routine of lsFusion
            // so far, the attachments data are available in EML export (exportEMLFile function)

            return emailContext.apply();
        }
        catch (MessagingException e) {
            LM.findProperty("conversionError[Itemail]").change(
                    "Cannot create Email: " + e.toString(), context, commandArgs);
            return false;
        }
    }

    private void readHeaders(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
        throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        try {
            // Get the message textual data
            String message = (String) LM.findProperty("message[Itemail]").
                    readClasses(context, commandArgs).getValue();
            InputStream messageStream = new ByteArrayInputStream(message.getBytes());

            // Initialize MimeMessage, where content is array of bytes
            // Because InputStream is defined as ByteArrayInputStream,
            // content is try from ASCIIUtility.getBytes((InputStream)) with parsed protected InternetHeaders
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);
            MimeMessage mimeMessage = new MimeMessage(session, messageStream);

            if (this.importEmail(context, commandArgs, mimeMessage)) {
                // Operation flag
                LM.findProperty("converted[Itemail]").change(LocalDateTime.now(), context, commandArgs);
            }
        }
        catch (MessagingException | IOException e) {
            LM.findProperty("conversionError[Itemail]").change(
                    "Cannot create MimeMessage: " + e.toString(), context, commandArgs);
        }
        context.apply();
    }

    private class MultipartBody {
        String message;
        Map<String, FileData> attachments;

        private MultipartBody(String message, Map<String, FileData> attachments) {
            this.message = message;
            this.attachments = attachments;
        }
    }
}
