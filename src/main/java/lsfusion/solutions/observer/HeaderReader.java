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
import lsfusion.server.physics.dev.integration.external.to.mail.EmailReceiver;
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

    public LocalDateTime getReceivedDate(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
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

    public boolean importEmail(ExecutionContext<ClassPropertyInterface> context,
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

            // Binary file
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            data.writeTo(out);
            LM.findProperty("emlFile[Email]").change(
                    new FileData(new RawFileData(out), "eml"), emailContext, emailObject);

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
