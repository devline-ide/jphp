package php.runtime.loader.dump;

import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.PropertyEntity;
import java.io.*;

/** ABI 6 property contracts in the pre-existing optional metadata slot. */
final class PropertyDeclarationDumper {
    private PropertyDeclarationDumper() {}
    static byte[] save(PropertyEntity property) throws IOException {
        if(property.getPhpSetModifier()!=null) {
            var bytes=new ByteArrayOutputStream();var data=new DumpOutputStream(bytes);
            data.writeInt(5);data.writeBoolean(property.isFinal());data.writeBoolean(property.isPromoted());data.writeBoolean(property.isPhpReadonly());
            data.writeByte(setCode(property));data.writeRawData(TypeDeclarationDumper.save(property.getDeclaredType()));return bytes.toByteArray();
        }
        if (property.getDeclaredType() == null && !property.isFinal() && !property.isPromoted() && !property.isPhpReadonly()) return null;
        var bytes = new ByteArrayOutputStream(); var data = new DumpOutputStream(bytes);
        data.writeInt(property.isPhpReadonly()?3:property.isPromoted()?2:1);data.writeBoolean(property.isFinal());
        if(property.isPromoted() || property.isPhpReadonly()) data.writeBoolean(property.isPromoted());
        if(property.isPhpReadonly()) data.writeBoolean(true);
        data.writeRawData(TypeDeclarationDumper.save(property.getDeclaredType()));
        return bytes.toByteArray();
    }
    static void load(PropertyEntity property, byte[] bytes) throws IOException {load(property,bytes,false);}
    private static void load(PropertyEntity property,byte[] bytes,boolean allowPhpSet)throws IOException {
        if (bytes == null || bytes.length == 0) return;
        var data = new DumpInputStream(new ByteArrayInputStream(bytes));
        int version=data.readInt();
        if(version==5) {
            if(!allowPhpSet)throw new DumpException("Asymmetric property visibility requires compiler ABI23");
            boolean fin=flag(data),prom=flag(data),readonly=flag(data);var set=readSet(data);
            var type=TypeDeclarationDumper.load(data.readRawData());
            if(data.available()!=0 || type==null || readonly && (property.isStatic() || property.isDefault()))
                throw new DumpException("Invalid asymmetric property declaration metadata");
            property.setFinal(fin);property.setPromoted(prom);property.setPhpReadonly(readonly);property.setDeclaredType(type);property.setPhpSetModifier(set);
            validateSet(property,fin);if(!property.isDefault())property.setDefaultValue(php.runtime.Memory.UNDEFINED);return;
        }
        if (version != 1 && version != 2 && version != 3) throw new DumpException("Unsupported property declaration metadata version");
        boolean isFinal = data.readBoolean();
        boolean promoted=version>=2 && data.readBoolean();
        int readonlyTag=version>=3?data.readUnsignedByte():0;
        if(readonlyTag>1) throw new DumpException("Invalid readonly property flag");
        boolean readonly=readonlyTag==1;
        var type = TypeDeclarationDumper.load(data.readRawData());
        if (data.available() != 0) throw new DumpException("Invalid property declaration metadata size");
        if(version==3 && !readonly) throw new DumpException("Noncanonical readonly property metadata");
        if(readonly && (type==null || property.isStatic() || property.isDefault())) throw new DumpException("Invalid readonly property declaration metadata");
        property.setPhpReadonly(readonly);
        property.setFinal(isFinal);
        property.setPromoted(promoted);
        property.setDeclaredType(type);
        if (property.getDeclaredType() != null && !property.isDefault()) property.setDefaultValue(php.runtime.Memory.UNDEFINED);
    }
    private static final int MAX_HOOK_METHOD_BYTES=1024*200;
    static final int MAX_HOOK_PROPERTY_BYTES=3*MAX_HOOK_METHOD_BYTES+64;
    static byte[] save(PropertyEntity property,MethodDumper methods,boolean rawStrings) throws IOException {
        var hooks=property.getPhpHooks();
        if(hooks==null) return save(property);
        hooks.validateFor(property);
        if(hooks.declaredOrder().isEmpty()) return save(property);
        if(property.isPhpReadonly()) throw new DumpException("Readonly property cannot have hooks");
        var bytes=new ByteArrayOutputStream();var data=new DumpOutputStream(bytes,rawStrings);
        data.writeInt(property.getPhpSetModifier()==null?4:6);data.writeBoolean(property.isFinal());data.writeBoolean(property.isPromoted());
        data.writeRawData(TypeDeclarationDumper.save(property.getDeclaredType()));
        if(property.getPhpSetModifier()!=null)data.writeByte(setCode(property));
        data.writeBoolean(hooks.isDeclaredVirtual());data.writeInt(hooks.declaredOrder().size());
        for(var hook:hooks.declaredOrder()) {
            if(hook.declaration()!=property) throw new IOException("Cannot encode inherited hook as owned declaration");
            data.writeByte(hook.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?1:2);
            var methodBytes=new ByteArrayOutputStream();
            methods.save(hook.method(),new DumpOutputStream(methodBytes,rawStrings));
            if(methodBytes.size()>MAX_HOOK_METHOD_BYTES) throw new DumpException("Property hook method payload too large");
            data.writeRawData(methodBytes.toByteArray(),MAX_HOOK_METHOD_BYTES);
        }
        return bytes.toByteArray();
    }
    static void load(PropertyEntity property,byte[] bytes,MethodDumper methods,boolean rawStrings,boolean allowPhpHooks)throws IOException {
        load(property,bytes,methods,rawStrings,allowPhpHooks,false);
    }
    static void load(PropertyEntity property,byte[] bytes,MethodDumper methods,boolean rawStrings,boolean allowPhpHooks,boolean allowPhpSet) throws IOException {
        if(bytes==null || bytes.length==0) return;
        var data=new DumpInputStream(new ByteArrayInputStream(bytes),rawStrings);
        int version=data.readInt();
        if(version!=4 && version!=6){load(property,bytes,allowPhpSet);return;}
        if(version==6 && !allowPhpSet)throw new DumpException("Asymmetric property visibility requires compiler ABI23");
        if(!allowPhpHooks) throw new DumpException("Property hooks require compiler ABI22");
        if(property.getClazz()!=null || property.getPhpHooks()!=null) throw new DumpException("Hook payload requires an unbound fresh property");
        PropertyEntity decoded=new PropertyEntity(methods.context);
        decoded.setName(property.getName());decoded.setStatic(property.isStatic());decoded.setModifier(property.getModifier());
        decoded.setDefault(property.isDefault());decoded.setDefaultValue(property.getDefaultValue());
        if(decoded.isStatic()) throw new DumpException("Static property cannot have hooks");
        boolean encodedFinal=flag(data);decoded.setFinal(encodedFinal);decoded.setPromoted(flag(data));
        decoded.setDeclaredType(TypeDeclarationDumper.load(data.readRawData()));
        if(version==6){decoded.setPhpSetModifier(readSet(data));validateSet(decoded,encodedFinal);}
        boolean virtual=flag(data);int count=data.readInt();
        if(count<1 || count>2) throw new DumpException("Invalid property hook count");
        var hooks=new java.util.ArrayList<php.runtime.reflection.support.PhpPropertyHooks.Hook>(count);
        int seen=0;
        for(int i=0;i<count;i++) {
            int kind=data.readUnsignedByte();
            if(kind<1 || kind>2 || (seen&(1<<kind))!=0) throw new DumpException("Invalid or duplicate property hook kind");
            seen|=1<<kind;
            byte[] methodBytes=data.readRawData(MAX_HOOK_METHOD_BYTES);
            if(methodBytes==null || methodBytes.length==0) throw new DumpException("Missing property hook method");
            var input=new DumpInputStream(new ByteArrayInputStream(methodBytes),rawStrings);
            var method=methods.load(input,1,true);
            if(input.available()!=0 || method.getInternalName()==null || method.getInternalName().isEmpty()
                    || method.getInternalName().indexOf('\0')>=0)
                throw new DumpException("Invalid property hook method bytes");
            try {hooks.add(new php.runtime.reflection.support.PhpPropertyHooks.Hook(
                    kind==1?php.runtime.reflection.support.PhpPropertyHooks.Kind.GET:php.runtime.reflection.support.PhpPropertyHooks.Kind.SET,
                    decoded,method));}
            catch(IllegalArgumentException malformed) {throw new DumpException("Invalid property hook method contract");}
        }
        if(data.available()!=0) throw new DumpException("Invalid property hook declaration metadata size");
        try {decoded.setPhpHooks(new php.runtime.reflection.support.PhpPropertyHooks(virtual,hooks));}
        catch(IllegalArgumentException malformed) {throw new DumpException("Invalid property hook storage contract");}
        if(decoded.getDeclaredType()!=null && !decoded.isDefault()) decoded.setDefaultValue(php.runtime.Memory.UNDEFINED);
        property.setFinal(decoded.isFinal());property.setPromoted(decoded.isPromoted());property.setPhpSetModifier(decoded.getPhpSetModifier());
        property.setDeclaredType(decoded.getDeclaredType());property.setDefaultValue(decoded.getDefaultValue());
        var rebound=new java.util.ArrayList<php.runtime.reflection.support.PhpPropertyHooks.Hook>();
        for(var hook:decoded.getPhpHooks().declarationOrder())
            rebound.add(new php.runtime.reflection.support.PhpPropertyHooks.Hook(hook.kind(),property,hook.method()));
        property.setPhpHooks(new php.runtime.reflection.support.PhpPropertyHooks(virtual,rebound));
    }
    private static int setCode(PropertyEntity property)throws IOException {
        if(property.getDeclaredType()==null)throw new DumpException("Asymmetric declaration lacks type");
        validateSet(property,property.isFinal());return property.getPhpSetModifier()==php.runtime.common.Modifier.PUBLIC?1:
                property.getPhpSetModifier()==php.runtime.common.Modifier.PROTECTED?2:3;
    }
    private static php.runtime.common.Modifier readSet(DumpInputStream data)throws IOException {
        return switch(data.readUnsignedByte()){case 1->php.runtime.common.Modifier.PUBLIC;case 2->php.runtime.common.Modifier.PROTECTED;
            case 3->php.runtime.common.Modifier.PRIVATE;default->throw new DumpException("Invalid asymmetric writer mode");};
    }
    private static void validateSet(PropertyEntity property,boolean encodedFinal)throws IOException {
        if(property.getDeclaredType()==null || php.runtime.PhpPropertyAccess.rank(property.effectiveSetModifier())<php.runtime.PhpPropertyAccess.rank(property.getModifier())
                || property.isPrivateSet() && !encodedFinal)throw new DumpException("Invalid asymmetric writer contract");
    }
    private static boolean flag(DumpInputStream data) throws IOException {
        int value=data.readUnsignedByte();
        if(value>1) throw new DumpException("Invalid property hook flag");
        return value==1;
    }
}
