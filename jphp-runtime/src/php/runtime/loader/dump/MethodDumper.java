package php.runtime.loader.dump;

import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.DocumentComment;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MethodDumper extends Dumper<MethodEntity> {
    protected ParameterDumper parameterDumper = new ParameterDumper(context, env, debugInformation);

    protected MethodDumper(Context context, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
    }

    @Override
    public int getType() {
        return Types.METHOD;
    }

    @Override
    public void save(MethodEntity entity, OutputStream output) throws IOException {
        DumpOutputStream print = new DumpOutputStream(output);

        DocumentComment docComment = entity.getDocComment();

        if (docComment != null) {
            print.writeUTF(docComment.toString());
        } else {
            print.writeUTF("");
        }

        // static
        print.writeBoolean(entity.isStatic());

        // final
        print.writeBoolean(entity.isFinal());

        // abstract
        print.writeBoolean(entity.isAbstract());

        // abstractable
        print.writeBoolean(entity.isAbstractable());

        // immutable
        print.writeBoolean(entity.isImmutable());
        print.writeMemory(entity.getImmutableResult());

        print.writeBoolean(entity.isEmpty());

        // ref
        print.writeBoolean(entity.isReturnReference());

        // type hinting.
        print.writeEnum(entity.getReturnType());
        print.writeName(entity.getReturnTypeClass());
        print.writeBoolean(entity.isReturnTypeNullable());

        // uses stack trace
        print.writeBoolean(entity.isUsesStackTrace());

        // modifier
        print.writeEnum(entity.getModifier());

        // name
        print.writeName(entity.getName());
        print.writeName(entity.getInternalName());

        // trace
        print.writeTrace(debugInformation ? entity.getTrace() : null);

        print.writeInt(entity.getParameters() == null ? 0 : entity.getParameters().length);

        if (entity.getParameters() != null)
        for(ParameterEntity param : entity.getParameters()){
            parameterDumper.save(param, output);
        }

        // raw data
        print.writeRawData(TypeDeclarationDumper.save(entity.getReturnTypeChecker()));
    }

    @Override
    public MethodEntity load(InputStream input) throws IOException {
        return load(input,Integer.MAX_VALUE,false);
    }

    MethodEntity load(InputStream input,int maximumParameters,boolean strictFlags) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        MethodEntity entity = new MethodEntity(context);

        String docComment = data.readUTF();

        if (!docComment.isEmpty()) {
            entity.setDocComment(new DocumentComment(docComment));
        }

        // static
        entity.setStatic( readFlag(data,strictFlags) );

        // final
        entity.setFinal( readFlag(data,strictFlags) );

        // abstract
        entity.setAbstract( readFlag(data,strictFlags) );
        entity.setAbstractable(readFlag(data,strictFlags));

        entity.setImmutable(readFlag(data,strictFlags));
        if(strictFlags) {
            if(data.readInt()!=-1) throw new DumpException("Hook method cannot contain a folded heap result");
            entity.setResult(null);
        } else entity.setResult(data.readMemory());

        entity.setEmpty(readFlag(data,strictFlags));

        // ref
        entity.setReturnReference( readFlag(data,strictFlags) );

        // type hinting
        entity.setReturnType(data.readHintType());
        String returnTypeClass = data.readName();

        if (returnTypeClass != null && !returnTypeClass.isEmpty()) {
            entity.setReturnTypeClass(returnTypeClass);
        }

        entity.setReturnTypeNullable(readFlag(data,strictFlags));

        // uses stack trace
        entity.setUsesStackTrace( readFlag(data,strictFlags) );

        // modifier
        entity.setModifier(data.readModifier());

        // name
        entity.setName(data.readName());
        entity.setInternalName(data.readName());

        // trace
        entity.setTrace(data.readTrace(context));

        int paramCount = data.readInt();
        if (paramCount < 0 || paramCount > maximumParameters)
            throw new DumpException("Invalid param count");

        entity.setParameters(new ParameterEntity[paramCount]);
        for(int i = 0; i < paramCount; i++){
            ParameterEntity param = parameterDumper.load(input,strictFlags);
            param.setTrace(entity.getTrace());
            entity.getParameters()[i] = param;
        }

        var declaration = TypeDeclarationDumper.load(data.readRawData());
        if (declaration != null) entity.setReturnTypeChecker(declaration);
        return entity;
    }
    private static boolean readFlag(DumpInputStream data,boolean strict) throws IOException {
        if(!strict) return data.readBoolean();
        int flag=data.readUnsignedByte();
        if(flag>1) throw new DumpException("Invalid hook method boolean flag");
        return flag==1;
    }
}
