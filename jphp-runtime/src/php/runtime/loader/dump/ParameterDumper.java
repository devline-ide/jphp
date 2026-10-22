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
        if (initializer == null && !entity.isPromoted()) print.writeRawData(type);
        else {
            var bytes = new java.io.ByteArrayOutputStream();
            var metadata = new DumpOutputStream(bytes);
            metadata.writeInt(0x5044494E); // PDIN: ABI8 executable parameter default descriptor.
            metadata.writeInt(initializer != null && initializer.constantOnly() ? 3 : entity.isPromoted() ? 2 : 1);
            metadata.writeRawData(type);
            if (initializer != null && initializer.constantOnly()) {
                metadata.writeBoolean(entity.isPromoted()); metadata.writeBoolean(true);
            } else if (entity.isPromoted()) {metadata.writeBoolean(true);metadata.writeBoolean(initializer != null);}
            if (initializer != null) {
                metadata.writeName(initializer.moduleName());
                metadata.writeName(initializer.javaClass());
                metadata.writeName(initializer.method());
                metadata.writeName(initializer.declarationScope());
                if (initializer.constantOnly()) metadata.writeEnum(initializer.kind());
            }
            print.writeRawData(bytes.toByteArray());
        }
    }

    @Override
    public ParameterEntity load(InputStream input) throws IOException {
        return load(input,false);
    }
    ParameterEntity load(InputStream input,boolean hook) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        ParameterEntity entity = new ParameterEntity(context);

        HintType hint = data.readHintType();
        if (hint != HintType.ANY) entity.setType(hint);
        String typeClass = data.readName();

        if (typeClass != null && !typeClass.isEmpty()) {
            entity.setTypeClass(typeClass);
        }

        entity.setReference(hookFlag(data,hook));

        entity.setMutable(hookFlag(data,hook));
        entity.setUsed(hookFlag(data,hook));
        entity.setVariadic(hookFlag(data,hook));
        entity.setNullable(hookFlag(data,hook));

        entity.setName(data.readName());
        entity.setTrace(data.readTrace(context));

        if(hook) {
            if(data.readInt()!=-1) throw new php.runtime.loader.dump.io.DumpException("Hook parameter cannot contain a default value");
            entity.setDefaultValue(null);
        } else entity.setDefaultValue(data.readMemory());
        entity.setDefaultValueConstName(data.readName());
        if(hook && entity.getDefaultValueConstName()!=null)throw new php.runtime.loader.dump.io.DumpException("Hook parameter cannot contain a default constant");

        byte[] metadata = data.readRawData();
        var raw = new DumpInputStream(new java.io.ByteArrayInputStream(metadata));
        ParameterEntity.DefaultInitializer initializer = null;
        byte[] type = metadata;
        if (metadata.length >= 4 && raw.readInt() == 0x5044494E) {
            int version=raw.readInt();
            if (version != 1 && version != 2 && version != 3) throw new php.runtime.loader.dump.io.DumpException("Unsupported parameter initializer version");
            type = raw.readRawData();
            if (version==2 || version==3) entity.setPromoted(hookFlag(raw,version==3));
            boolean hasInitializer=version==1 || hookFlag(raw,version==3);
            try {
                if (hasInitializer) {
                    String module=raw.readName(), clazz=raw.readName(), method=raw.readName(), scope=raw.readName();
                    int kindCode=version==3 ? raw.readInt() : 0;
                    if (kindCode != (version==3 ? 1 : 0))
                        throw new IllegalArgumentException("Invalid constant-only parameter default kind");
                    var kind=ParameterEntity.DefaultInitializer.Kind.values()[kindCode];
                    if (version==3 && kind!=ParameterEntity.DefaultInitializer.Kind.CONSTANT_EXPRESSION)
                        throw new IllegalArgumentException("Invalid constant-only parameter default kind");
                    initializer = new ParameterEntity.DefaultInitializer(module,clazz,method,scope,kind);
                } else if (version==3) throw new IllegalArgumentException("Constant-only parameter default has no initializer");
            }
            catch (IllegalArgumentException invalid) { throw new php.runtime.loader.dump.io.DumpException(invalid.getMessage()); }
            if (raw.available() != 0) throw new php.runtime.loader.dump.io.DumpException("Invalid parameter initializer size");
            if (initializer != null && (entity.getDefaultValue() == null || !entity.getDefaultValue().isNull() || entity.getDefaultValueConstName() != null && !initializer.constantOnly()))
                throw new php.runtime.loader.dump.io.DumpException("Invalid parameter initializer placeholder");
        }
        if(hook && (initializer!=null || entity.isPromoted()))throw new php.runtime.loader.dump.io.DumpException("Hook parameter cannot be promoted or initialized");
        var declaration = TypeDeclarationDumper.load(type);
        if (declaration != null) entity.setTypeChecker(declaration);
        if (initializer != null) entity.setDefaultInitializer(initializer);
        return entity;
    }
    private static boolean hookFlag(DumpInputStream data,boolean hook) throws IOException {
        if(!hook)return data.readBoolean();
        int flag=data.readUnsignedByte();
        if(flag>1)throw new php.runtime.loader.dump.io.DumpException("Invalid hook parameter boolean flag");
        return flag==1;
    }
}
