package lsfusion.solutions.observer;

import lsfusion.base.ExceptionUtils;
import lsfusion.server.data.sql.exception.SQLHandledException;
import lsfusion.server.language.ScriptingErrorLog;
import lsfusion.server.language.ScriptingLogicsModule;
import lsfusion.server.logics.action.controller.context.ExecutionContext;
import lsfusion.server.logics.classes.ValueClass;
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
    protected void executeInternal(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        try {
            DataObject commandArgs = context.getDataKeyValue(mboxInterface);
            importMbox(context, commandArgs);
        }
        catch (Exception e) {
            throw ExceptionUtils.propagate(e, SQLException.class, SQLHandledException.class);
        }
    }

    private void importMbox(ExecutionContext<ClassPropertyInterface> context, DataObject commandArgs) throws IOException, ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        try {
            // File link
            FileInputStream fileInput = new FileInputStream(
                (String) findProperty("filelink[Mbox]").readClasses(context, commandArgs).getValue());
            // Recognition of file content in UTF8
            InputStreamReader filestream = new InputStreamReader(fileInput, StandardCharsets.UTF_8);
            // Buffer for file content
            BufferedReader importer = new BufferedReader(filestream);
            // Buffer for strings
            String next = "";
            // File reading
            while ((next = importer.readLine()) != null) {
                System.out.println(next);
            }
            // Success operation flag
            findProperty("isImported[Mbox]").change(true, context, commandArgs);
        }
        catch (Exception e)
        {
            findProperty("messageCaughtException").change(e.toString(),context,commandArgs);
        }
    }
}
