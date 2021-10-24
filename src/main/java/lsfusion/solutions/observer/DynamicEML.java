package lsfusion.solutions.observer;

import lsfusion.base.ExceptionUtils;
import lsfusion.base.file.FileData;
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
import lsfusion.server.physics.dev.integration.internal.to.InternalAction;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;

public class DynamicEML extends InternalAction {

    // Used to transfer parameters from the LSFusion module
    private final ClassPropertyInterface itemailInterface;

    // Date format for the From_ line timestamp
    private static final SimpleDateFormat simpleDateFormat =
            new SimpleDateFormat("EEE MMM dd HH:mm:ss ZZZZ yyyy", Locale.ENGLISH);

    public DynamicEML(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        itemailInterface = i.next();
    }

    @Override
    protected void executeInternal(ExecutionContext<ClassPropertyInterface> context)
            throws SQLException, SQLHandledException {
        try {
            DataObject commandArgs = context.getDataKeyValue(itemailInterface);
            this.exportEML(context, commandArgs);
        }
        catch (Exception e) {
            throw ExceptionUtils.propagate(e, SQLException.class, SQLHandledException.class);
        }
    }

    public void exportEML(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
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

            // Binary file
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mimeMessage.writeTo(out);
            LM.findProperty("emlFileExport[Itemail]").change(
                    new FileData(new RawFileData(out), "eml"), context, commandArgs);
        }
        catch (MessagingException | IOException e) {
            LM.findProperty("conversionError[Itemail]").change(
                    "Cannot export item into EML file: " + e.toString(), context, commandArgs);
            context.apply();
        }
    }
}
