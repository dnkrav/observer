package lsfusion.solutions.observer;

import lsfusion.base.ExceptionUtils;
import lsfusion.server.base.exception.ApplyCanceledException;
import lsfusion.server.data.sql.exception.SQLHandledException;
import lsfusion.server.language.ScriptingErrorLog;
import lsfusion.server.language.ScriptingLogicsModule;
import lsfusion.server.logics.action.controller.context.ExecutionContext;
import lsfusion.server.logics.classes.ValueClass;
import lsfusion.server.logics.classes.user.ConcreteCustomClass;
import lsfusion.server.logics.property.classes.ClassPropertyInterface;
import lsfusion.server.physics.dev.integration.internal.to.InternalAction;
import lsfusion.server.data.value.DataObject;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Iterator;

public class MboxImporter extends InternalAction {

    // Used to transfer parameters from the LSFusion module
    private final ClassPropertyInterface mboxInterface;

    public MboxImporter(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        mboxInterface = i.next();
    }

    @Override
    protected void executeInternal(ExecutionContext<ClassPropertyInterface> context)
            throws SQLException, SQLHandledException {
        try {
            DataObject commandArgs = context.getDataKeyValue(mboxInterface);
            importMbox(context, commandArgs);
        }
        catch (Exception e) {
            throw ExceptionUtils.propagate(e, SQLException.class, SQLHandledException.class);
        }
    }

    private static long countLines(String fileLink)
            throws IOException {
        long count = 0;

        FileInputStream fileInput = new FileInputStream(fileLink);
        InputStreamReader filestream = new InputStreamReader(fileInput, StandardCharsets.UTF_8);
        BufferedReader importer = new BufferedReader(filestream);

        while (importer.readLine() != null) {
            count++;
        }

        importer.close();
        filestream.close();
        fileInput.close();

        return count;
    }

    private long countLines(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs,
                              String fileLink)
            throws IOException, ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        long count;
        try {
            count = (long) LM.findProperty("length[Mbox]").readClasses(context, commandArgs).getValue();
        } catch (NullPointerException emptyLength) {
            count = countLines(fileLink);
            // Store the file length
            try (ExecutionContext.NewSession<ClassPropertyInterface> itemContext = context.newSession()) {
                LM.findProperty("length[Mbox]").change(count, itemContext, commandArgs);
                itemContext.applyException();
            }
            catch (ApplyCanceledException e) {
                LM.findProperty("lastImporterOutput").
                        change("Database Access: Cannot store file length of " + count + ": " + e.toString(),
                                context,commandArgs);
                return 0;
            }
        }
        return count;
    }

    private boolean writeItem(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs,
                              Itemail itemail, long progress) {
        // Create new object and store it in the database within a separate session
        try (ExecutionContext.NewSession<ClassPropertyInterface> itemContext = context.newSession()) {
            DataObject itemObject = itemContext.addObject((ConcreteCustomClass) LM.findClass("Itemail"));

            LM.findProperty("mbox[Itemail]").change(commandArgs, itemContext, itemObject);

            LM.findProperty("envsender[Itemail]").change(itemail.addr, itemContext, itemObject);
            LM.findProperty("timestamp[Itemail]").change(itemail.date, itemContext, itemObject);
            LM.findProperty("message[Itemail]").change(itemail.message(), itemContext, itemObject);

            LM.findProperty("progress[Mbox]").change(progress, itemContext, commandArgs);

            return itemContext.apply();
        }
        catch (SQLException | SQLHandledException | ScriptingErrorLog.SemanticErrorException e) {
            // SQL access issues, don't stop file reading
            return false;
        }
    }

    private void importMbox(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
            throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        try {
            String fileLink = (String) LM.findProperty("filelink[Mbox]").
                    readClasses(context, commandArgs).getValue();

            // Initialize progress
            if (countLines(context, commandArgs, fileLink) == 0) {
                return;
            }
            long number = 0;
            int messages = 0;
            int recorded = 0;

            // Recognition of file content in UTF8
            FileInputStream fileInput = new FileInputStream(fileLink);
            InputStreamReader filestream = new InputStreamReader(fileInput, StandardCharsets.UTF_8);

            // Buffers for file content
            BufferedReader importer = new BufferedReader(filestream);
            String next;
            boolean confidence;
            FromLine fromLine = new FromLine();
            Itemail itemail;

            // First run
            next = importer.readLine();
            confidence = fromLine.checkFromLine(next);
            if (!confidence) {
                throw new IOException("Wrong MBOX archive, doesn't starts from the From line");
            } else {
                itemail = new Itemail(fromLine);
            }

            // File reading
            do {
                // Splitting strings message by message
                next = importer.readLine();
                number++;

                confidence = fromLine.checkFromLine(next);

                // Single message processing
                if (fromLine.isFromLine | next == null) {
                    messages++;
                    if (writeItem(context, commandArgs, itemail, number)) {
                        recorded++;
                    }
                    itemail = new Itemail(fromLine);
                } else {
                    itemail.appendMessageLine(next);
                }

                // Business logic exception made here, because we want the questionable item to be analyzed
                if (!confidence) {
                    LM.findProperty("lastImporterOutput").
                            change("From Line parsing: regex match is not confident, check the last Message: " +
                                    itemail.fromLine, context,commandArgs);
                    return;
                }
            } while (next != null);

            // Success operation flags
            LM.findProperty("messages[Mbox]").change(messages, context, commandArgs);
            LM.findProperty("isImported[Mbox]").change(true, context, commandArgs);
            LM.findProperty("lastImporterResult").change("Success", context, commandArgs);
            LM.findProperty("lastImporterOutput").
                    change("Found " + messages + " messages, recorded: " + recorded, context, commandArgs);

            importer.close();
            filestream.close();
            fileInput.close();
        }
        catch (IOException e)
        {
            LM.findProperty("lastImporterOutput").
                    change("Finished with IOException: " + e.toString(),context,commandArgs);
        }
    }
}
