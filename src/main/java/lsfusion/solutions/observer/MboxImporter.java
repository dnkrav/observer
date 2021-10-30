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
import lsfusion.server.physics.admin.log.ServerLoggers;
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

    // From_ line tracking buffer
    private FromLine fromLine = new FromLine();

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
            this.importMbox(context, commandArgs);
        }
        catch (Exception e) {
            throw ExceptionUtils.propagate(e, SQLException.class, SQLHandledException.class);
        }
    }

    private static long[] getFileSize(String fileLink)
            throws IOException {
        long linesCount = 0;

        FileInputStream fileInput = new FileInputStream(fileLink);
        InputStreamReader filestream = new InputStreamReader(fileInput, StandardCharsets.UTF_8);
        BufferedReader importer = new BufferedReader(filestream);

        long size = fileInput.getChannel().size();

        while (importer.readLine() != null) {
            linesCount++;
        }

        importer.close();
        filestream.close();
        fileInput.close();

        return new long[]{size, linesCount};
    }

    private long queryProgress(ExecutionContext<ClassPropertyInterface> context, DataObject obj, String parameter)
            throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        try {
            return (long) LM.findProperty(parameter).readClasses(context, obj).getValue();
        } catch (NullPointerException emptyValue) {
            return 0;
        }
    }

    private long[] countFileContent(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs,
                              String fileLink)
            throws IOException, ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        long[] count = new long[] {
                this.queryProgress(context,commandArgs,"length[Mbox]"),
                this.queryProgress(context,commandArgs,"progress[Mbox]"),
                this.queryProgress(context,commandArgs,"bytes[Mbox]")
        };
        if (count[0] == 0) {
            long[] dims = getFileSize(fileLink);
            // Store the file length
            try (ExecutionContext.NewSession<ClassPropertyInterface> itemContext = context.newSession()) {
                LM.findProperty("size[Mbox]").change(dims[0], itemContext, commandArgs);
                LM.findProperty("length[Mbox]").change(dims[1], itemContext, commandArgs);
                itemContext.applyException();
                count[0] = dims[1];
            }
            catch (ApplyCanceledException e) {
                String warnMessage = "Database Access: Cannot store file length of " + count[0] + ": " + e.toString();
                ServerLoggers.importLogger.warn(warnMessage);
                LM.findProperty("lastImporterOutput").change(warnMessage, context, commandArgs);
            }
        }
        return count;
    }

    private boolean writeItem(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs,
                              Itemail itemail, long progress, long position)
            throws IOException {
        // Create new object and store it in the database within a separate session
        try (ExecutionContext.NewSession<ClassPropertyInterface> itemContext = context.newSession()) {
            DataObject itemObject = itemContext.addObject((ConcreteCustomClass) LM.findClass("Itemail"));

            LM.findProperty("mbox[Itemail]").change(commandArgs, itemContext, itemObject);

            LM.findProperty("envsender[Itemail]").change(itemail.addr, itemContext, itemObject);
            LM.findProperty("timestamp[Itemail]").change(itemail.date, itemContext, itemObject);
            LM.findProperty("message[Itemail]").change(itemail.message(), itemContext, itemObject);

            LM.findProperty("progress[Mbox]").change(progress, itemContext, commandArgs);
            LM.findProperty("bytes[Mbox]").change(position, itemContext, commandArgs);

            return itemContext.apply();
        } catch (SQLException | SQLHandledException | ScriptingErrorLog.SemanticErrorException e) {
            // SQL access issues, don't stop file reading
            ServerLoggers.importLogger.warn("Cannot write message from MBOX " + itemail.addr + ": " + e.getMessage());
            return false;
        } catch (OutOfMemoryError e) {
            // Monitor the memory overfilling
            throw new IOException("Message item is too large for JVM memory of " +
                    Runtime.getRuntime().maxMemory() + ". Occurred from " + itemail.addr + " at " + position + " bytes");
        }
    }

    private void importMbox(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs)
            throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        try {
            String fileLink = (String) LM.findProperty("filelink[Mbox]").
                    readClasses(context, commandArgs).getValue();

            // Initialize progress
            long[] count = this.countFileContent(context, commandArgs, fileLink);
            if (count[0] == 0) {
                // file size is undefined
                return;
            }
            long number = 0;
            int recorded = 0;

            // Recognition of file content in UTF8
            FileInputStream fileInput = new FileInputStream(fileLink);
            InputStreamReader filestream = new InputStreamReader(fileInput, StandardCharsets.UTF_8);

            // Buffers for file content
            // Buffer must be larger, than size of the From_ line
            int defaultCharBufferSize = 8192;
            BufferedReader importer = new BufferedReader(filestream, defaultCharBufferSize);
            String next = " ";
            Itemail itemail;
            long position = fileInput.getChannel().position();

            // First run or skip previously read characters
            // Position in encounted in bytes
            // Buffer reads chars, Size of char is 2 bytes
            // Position skip is given in bytes
            // The jump is decreased by buffer for the next line after last message and another buffer for shift
            // Plus extra buffer to cover EOL with confidence
            //if (count[2] > (defaultCharBufferSize * 2 + 2)) {
            //    long skip = count[2] / 2 - defaultCharBufferSize - 1;
            if (count[2] > (defaultCharBufferSize * 2 * 2 + 8)) {
                long skip = count[2] - defaultCharBufferSize * 4 - 8;
                position = importer.skip(skip);
            }
            if (position == 0) {
                // @ToDo compare performance
                while (number < count[1] && next != null) {
                    next = importer.readLine();
                    number++;
                }
            }
            //position = fileInput.getChannel().position(); // for debug
            do {
                next = importer.readLine();
                number++;
            } while (!fromLine.checkFromLine(next) && next != null);
            position = fileInput.getChannel().position();
            // We must be here around the same position as at previous stop
            if (count[2] > 0 && count[2] != position) {
                // Wrong re-positioning
                ServerLoggers.importLogger.warn(
                        "Not precise in " + fileLink + " successful to byte " + position + " instead of " + count[2]);
            }
            if (count[2] > 0 && count[2] == position) {
                ServerLoggers.importLogger.info("Repositioning in " + fileLink + " successful to byte " + position);
            }
            number = count[1] + 1;
            if (next == null) {
                throw new IOException("Cannot find From_ line in MBOX archive");
            } else {
                itemail = new Itemail(fromLine);
            }

            // File reading
            do {
                // Splitting strings message by message
                next = importer.readLine();
                // 1-based number of currently read line
                number++;

                // Single message processing
                if (fromLine.checkFromLine(next) || next == null) {
                    position = fileInput.getChannel().position();
                    // itemail contains now lines up to current exclusively
                    if (this.writeItem(context, commandArgs, itemail, number-1, position)) {
                        ServerLoggers.importLogger.info(
                                "Successfully imported message from MBOX " + itemail.addr + " at " + position);
                        recorded++;
                    }
                    itemail = new Itemail(fromLine);
                } else {
                    itemail.appendMessageLine(next);
                }

                // Business logic exception made here, because we want the questionable item to be analyzed
                if (!fromLine.confidence) {
                    // Logging strange parsing against the RFC format
                    ServerLoggers.importLogger.warn(
                            "Non-confident From line of message " + next);
                }
            } while (next != null);

            // Success operation flags
            LM.findProperty("isImported[Mbox]").change(true, context, commandArgs);
            LM.findProperty("lastImporterResult").change("Success", context, commandArgs);
            LM.findProperty("lastImporterOutput").change(
                    "Read " + position + " bytes, recorded " + recorded + " messages", context, commandArgs);

            importer.close();
            filestream.close();
            fileInput.close();
        }
        catch (IOException e)
        {
            // This handling includes OutOfMemoryError exceptions from called routines
            ServerLoggers.importLogger.error(e.getMessage());
            LM.findProperty("lastImporterOutput").
                    change("Finished with IOException: " + e.toString(),context,commandArgs);
        } catch (OutOfMemoryError e) {
            // Monitor the memory overfilling
            ServerLoggers.importLogger.error("BufferedReader position is too large for JVM memory of " +
                    Runtime.getRuntime().maxMemory() + ". Occurred from " + fromLine.addr);
            LM.findProperty("lastImporterOutput").
                    change("At line from " + fromLine.addr + " finished with OutOfMemoryError: " + e.toString(),
                            context,commandArgs);
        }
    }
}
