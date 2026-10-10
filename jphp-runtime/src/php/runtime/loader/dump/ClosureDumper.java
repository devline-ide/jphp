package php.runtime.loader.dump;

import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.Closure;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.helper.ClosureEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

public class ClosureDumper extends Dumper<ClosureEntity> {
    public static final int CAPTURE_PLAN_STAMP = 0x43504C4E; // CPLN, explicit ABI9 closure tail.
    private static final int CAPTURE_PLAN_VERSION = 1;
    protected ParameterDumper parameterDumper = new ParameterDumper(context, env, debugInformation);
    protected final MethodDumper methodDumper = new MethodDumper(context, env, debugInformation);

    public ClosureDumper(Context context, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
    }

    private String readCaptureName(DumpInputStream data) throws IOException {
        int length = data.readInt();
        if (length <= 0 || length > 2048) throw new DumpException("Invalid closure capture name size");
        String name = data.readUTF();
        if (name.length() != length) throw new DumpException("Invalid closure capture name length");
        return name;
    }

    @Override
    public int getType() {
        return Types.CLOSURE;
    }

    @Override
    public void save(ClosureEntity entity, OutputStream output) throws IOException {
        if (!entity.isLoaded())
            throw new DumpException("Closure not loaded");

        DumpOutputStream data = new DumpOutputStream(output);

        data.writeName(entity.getInternalName());
        data.writeBoolean(entity.isReturnReference());

        methodDumper.save(entity.methodMagicInvoke, output);

        data.writeInt(entity.parameters == null ? 0 : entity.parameters.length);
        if (entity.parameters != null)
            for(ParameterEntity param : entity.parameters){
                parameterDumper.save(param, output);
            }


        data.writeInt(entity.uses == null ? 0 : entity.uses.length);
        if (entity.uses != null)
            for(ParameterEntity param : entity.uses){
                parameterDumper.save(param, output);
            }

        if (includeData) {
            data.writeRawData(entity.getData(), Integer.MAX_VALUE);
        } else {
            data.writeRawData(null);
        }
        var plan = includeData ? entity.getOwnershipCapturePlan() : null;
        if (plan == null) data.writeRawData(null);
        else {
            var bytes = new ByteArrayOutputStream();
            var metadata = new DumpOutputStream(bytes);
            metadata.writeInt(CAPTURE_PLAN_STAMP); metadata.writeInt(CAPTURE_PLAN_VERSION);
            metadata.writeName(plan.generatedClassName()); metadata.writeName(plan.bytecodeSha256());
            metadata.writeBoolean(plan.staticClosure()); metadata.writeBoolean(plan.usesThis());
            metadata.writeInt(plan.entries().size());
            for (var entry : plan.entries()) {
                metadata.writeName(entry.name()); metadata.writeByte(entry.mode() == ClosureEntity.CaptureMode.VALUE ? 1 : 2);
            }
            data.writeRawData(bytes.toByteArray());
        }
    }

    @Override
    public ClosureEntity load(InputStream input) throws IOException {
        return load(input, ModuleDumper.COMPILER_ABI_VERSION);
    }

    public ClosureEntity load(InputStream input, int compilerAbi) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        ClosureEntity entity = new ClosureEntity(context);
        entity.setParent(env.scope.fetchUserClass(Closure.class));

        entity.setInternalName(data.readName());
        entity.setReturnReference(data.readBoolean());

        entity.methodMagicInvoke = methodDumper.load(input);
        entity.methodMagicInvoke.setClazz(entity);
        entity.addMethod(entity.methodMagicInvoke, null);

        int paramCount = data.readInt();
        if (paramCount < 0)
            throw new DumpException("Invalid param count");

        entity.parameters = new ParameterEntity[paramCount];
        for(int i = 0; i < paramCount; i++){
            ParameterEntity param = parameterDumper.load(input);
            param.setTrace(entity.getTrace());
            entity.parameters[i] = param;
        }

        paramCount = data.readInt();
        if (paramCount < 0)
            throw new DumpException("Invalid param count");

        entity.uses = new ParameterEntity[paramCount];
        for(int i = 0; i < paramCount; i++){
            ParameterEntity param = parameterDumper.load(input);
            param.setTrace(entity.getTrace());
            entity.uses[i] = param;
        }

        entity.setData(data.readRawData(Integer.MAX_VALUE));
        if (compilerAbi >= 9) {
            byte[] bytes = data.readRawData(1024 * 1024 * 5);
            if (bytes.length != 0) {
                var metadata = new DumpInputStream(new ByteArrayInputStream(bytes));
                if (metadata.readInt() != CAPTURE_PLAN_STAMP)
                    throw new DumpException("Invalid closure capture plan signature");
                if (metadata.readInt() != CAPTURE_PLAN_VERSION)
                    throw new DumpException("Unsupported closure capture plan version");
                String generatedClass = readCaptureName(metadata), digest = readCaptureName(metadata);
                int staticFlag = metadata.readUnsignedByte(), thisFlag = metadata.readUnsignedByte();
                if (staticFlag > 1 || thisFlag > 1) throw new DumpException("Invalid closure capture flags");
                boolean staticClosure = staticFlag == 1, usesThis = thisFlag == 1;
                int count = metadata.readInt();
                if (count < 0 || count != entity.uses.length)
                    throw new DumpException("Invalid closure capture plan count");
                var entries = new ArrayList<ClosureEntity.CaptureEntry>(count);
                try {
                    for (int i = 0; i < count; i++) {
                        String name = readCaptureName(metadata); int mode = metadata.readUnsignedByte();
                        if (mode != 1 && mode != 2) throw new DumpException("Invalid closure capture mode");
                        entries.add(new ClosureEntity.CaptureEntry(name, mode == 1
                                ? ClosureEntity.CaptureMode.VALUE : ClosureEntity.CaptureMode.CELL));
                    }
                    if (metadata.available() != 0) throw new DumpException("Invalid closure capture plan size");
                    entity.recordDumpCapturePlan(generatedClass, digest, staticClosure, usesThis, entries);
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new DumpException(invalid.getMessage());
                }
            }
        }
        return entity;
    }
}
