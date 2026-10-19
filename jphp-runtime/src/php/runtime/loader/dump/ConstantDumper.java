package php.runtime.loader.dump;

import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.DocumentComment;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import php.runtime.loader.dump.io.DumpException;
import java.io.InputStream;
import java.io.OutputStream;


public class ConstantDumper extends Dumper<ConstantEntity> {
    public ConstantDumper(Context context, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
    }

    @Override
    public int getType() {
        return Types.CONSTANT;
    }

    @Override
    public void save(ConstantEntity entity, OutputStream output) throws IOException {
        DumpOutputStream dump = new DumpOutputStream(output);

        DocumentComment docComment = entity.getDocComment();

        if (docComment != null) {
            dump.writeUTF(docComment.toString());
        } else {
            dump.writeUTF("");
        }

        dump.writeBoolean(entity.isCaseSensitise());
        dump.writeEnum(entity.getModifier());
        dump.writeName(entity.getName());
        dump.writeTrace(debugInformation ? null : entity.getTrace());
        dump.writeMemory(entity.getStoredValue());

        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        DumpOutputStream attributes = new DumpOutputStream(metadata);
        attributes.writeInt(entity.isEnumCase()?4:3);
        attributes.writeBoolean(entity.isFinal());
        attributes.writeName(entity.getTraitName());
        attributes.writeName(entity.getInitializerClass());
        attributes.writeName(entity.getInitializerMethod());
        var type = entity.getDeclaredType();
        attributes.writeInt(type == null ? 0 : type.getAlternatives().size());
        if (type != null) for (var arm : type.getAlternatives()) {
            attributes.writeInt(arm.size()); for (String name : arm) attributes.writeName(name);
        }
        if(entity.isEnumCase())attributes.writeBoolean(true);
        dump.writeRawData(metadata.toByteArray());
    }

    @Override
    public ConstantEntity load(InputStream input) throws IOException {
        DumpInputStream dump = new DumpInputStream(input);

        String docComment = dump.readUTF();

        boolean cs = dump.readBoolean();
        Modifier modifier = dump.readModifier();
        String name = dump.readName();
        TraceInfo trace = dump.readTrace(context);
        Memory value = dump.readMemory();

        ConstantEntity entity = new ConstantEntity(name, value, cs);
        entity.setContext(context);
        entity.setTrace(trace);
        entity.setModifier(modifier);

        if (!docComment.isEmpty()) {
            entity.setDocComment(new DocumentComment(docComment));
        }

        byte[] metadata = dump.readRawData();
        if (metadata != null && metadata.length != 0) {
            DumpInputStream attributes = new DumpInputStream(new ByteArrayInputStream(metadata));
            int version = attributes.readInt();
            if (version < 1 || version > 4) throw new DumpException("Unsupported class constant metadata version");
            entity.setFinal(attributes.readBoolean());
            entity.setTraitName(attributes.readName());
            if (version >= 2) entity.setInitializer(attributes.readName(), attributes.readName());
            if (version >= 3) {
                int alternatives = attributes.readInt();
                if (alternatives < 0 || alternatives > attributes.available() / 11) throw new DumpException("Invalid type alternatives");
                java.util.List<java.util.List<String>> arms = new java.util.ArrayList<>();
                for (int i=0; i<alternatives; i++) {
                    int members = attributes.readInt();
                    if (members < 1 || members > attributes.available() / 7) throw new DumpException("Invalid type members");
                    java.util.List<String> arm = new java.util.ArrayList<>();
                    for (int j=0; j<members; j++) {
                        String memberName = attributes.readName(32768);
                        if (memberName == null || memberName.isEmpty()) throw new DumpException("Invalid type name");
                        arm.add(memberName);
                    }
                    arms.add(arm);
                }
                if (alternatives > 0) entity.setDeclaredType(new php.runtime.reflection.support.PhpTypeDeclaration(arms));
            }
            if(version>=4){if(attributes.readUnsignedByte()!=1)throw new DumpException("Invalid enum case flag");entity.setEnumCase(true);}
            if (attributes.available() != 0) throw new DumpException("Invalid class constant metadata size");
        }
        return entity;
    }
}
