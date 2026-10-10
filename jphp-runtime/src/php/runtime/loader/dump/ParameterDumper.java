package php.runtime.loader.dump;

import php.runtime.common.StringUtils;
import php.runtime.common.HintType;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.ParameterEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ParameterDumper extends Dumper<ParameterEntity> {
    public ParameterDumper(Context context, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
    }

    @Override
    public int getType() {
        return Types.PARAMETER;
    }

    @Override
    public void save(ParameterEntity entity, OutputStream output) throws IOException {
        DumpOutputStream print = new DumpOutputStream(output);

        // hint type
        print.writeEnum(entity.getType());

        // hint class type
        print.writeName(entity.getTypeClass());

        // & ref
        print.writeBoolean(entity.isReference());

        // mutable
        print.writeBoolean(entity.isMutable());

        // used
        print.writeBoolean(entity.isUsed());

        // variadic
        print.writeBoolean(entity.isVariadic());

        // nullable
        print.writeBoolean(entity.isNullable());

        // name
        print.writeName(entity.getName());

        // trace
        print.writeTrace(debugInformation ? entity.getTrace() : null);

        // memory
        print.writeMemory(entity.getDefaultValue());
        print.writeName(entity.getDefaultValueConstName());

        // raw
        byte[] type = TypeDeclarationDumper.save(entity.getTypeChecker());
        var initializer = entity.getDefaultInitializer();
        if (initializer == null) print.writeRawData(type);
        else {
            var bytes = new java.io.ByteArrayOutputStream();
            var metadata = new DumpOutputStream(bytes);
            metadata.writeInt(0x5044494E); // PDIN: ABI8 executable parameter default descriptor.
            metadata.writeInt(1);
            metadata.writeRawData(type);
            metadata.writeName(initializer.moduleName());
            metadata.writeName(initializer.javaClass());
            metadata.writeName(initializer.method());
            metadata.writeName(initializer.declarationScope());
            print.writeRawData(bytes.toByteArray());
        }
    }

    @Override
    public ParameterEntity load(InputStream input) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        ParameterEntity entity = new ParameterEntity(context);

        HintType hint = data.readHintType();
        if (hint != HintType.ANY) entity.setType(hint);
        String typeClass = data.readName();

        if (typeClass != null && !typeClass.isEmpty()) {
            entity.setTypeClass(typeClass);
        }

        entity.setReference(data.readBoolean());

        entity.setMutable(data.readBoolean());
        entity.setUsed(data.readBoolean());
        entity.setVariadic(data.readBoolean());
        entity.setNullable(data.readBoolean());

        entity.setName(data.readName());
        entity.setTrace(data.readTrace(context));

        entity.setDefaultValue(data.readMemory());
        entity.setDefaultValueConstName(data.readName());

        byte[] metadata = data.readRawData();
        var raw = new DumpInputStream(new java.io.ByteArrayInputStream(metadata));
        ParameterEntity.DefaultInitializer initializer = null;
        byte[] type = metadata;
        if (metadata.length >= 4 && raw.readInt() == 0x5044494E) {
            if (raw.readInt() != 1) throw new php.runtime.loader.dump.io.DumpException("Unsupported parameter initializer version");
            type = raw.readRawData();
            try { initializer = new ParameterEntity.DefaultInitializer(raw.readName(), raw.readName(), raw.readName(), raw.readName()); }
            catch (IllegalArgumentException invalid) { throw new php.runtime.loader.dump.io.DumpException(invalid.getMessage()); }
            if (raw.available() != 0) throw new php.runtime.loader.dump.io.DumpException("Invalid parameter initializer size");
            if (entity.getDefaultValue() == null || !entity.getDefaultValue().isNull() || entity.getDefaultValueConstName() != null)
                throw new php.runtime.loader.dump.io.DumpException("Invalid parameter initializer placeholder");
        }
        var declaration = TypeDeclarationDumper.load(type);
        if (declaration != null) entity.setTypeChecker(declaration);
        if (initializer != null) entity.setDefaultInitializer(initializer);
        return entity;
    }
}
