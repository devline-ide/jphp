package php.runtime.loader.dump;

import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.env.TraceInfo;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.GeneratorEntity;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class ClassDumper extends Dumper<ClassEntity> {
    @FunctionalInterface private interface RelationLink {void link() throws IOException;}
    private boolean deferLinking;
    private final List<RelationLink> deferredLinks=new ArrayList<>();
    public void setDeferLinking(boolean value) {deferLinking=value;}
    public void setAllowPhpHooks(boolean value) {propertyDumper.setAllowPhpHooks(value);}
    public void setAllowPhpSet(boolean value) {propertyDumper.setAllowPhpSet(value);}
    public void linkDecodedDeclarations() throws IOException {
        for(RelationLink link:deferredLinks) link.link();deferredLinks.clear();
    }
    protected final PropertyDumper propertyDumper = new PropertyDumper(context, env, debugInformation);
    protected final ConstantDumper constantDumper = new ConstantDumper(context, env, debugInformation);
    protected final MethodDumper methodDumper = new MethodDumper(context, env, debugInformation);

    protected final ModuleEntity module;

    public ClassDumper(Context context, ModuleEntity module, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
        this.module = module;
        if(module!=null) {
            var provenance=module.getCompilerProvenance();
            propertyDumper.setAllowPhpHooks(provenance.getCompilerAbiVersion()>=22 || provenance.getAcceptedDumpAbiVersion()>=22);
            propertyDumper.setAllowPhpSet(provenance.getCompilerAbiVersion()>=23 || provenance.getAcceptedDumpAbiVersion()>=23);
        }
    }

    @Override
    public int getType() {
        return Types.CLASS;
    }

    @Override
    public void save(ClassEntity entity, OutputStream output) throws IOException {
        if (entity.getType() == ClassEntity.Type.CLASS && entity.getData() == null)
            throw new DumpException("Class '" + entity.getName() + "' not compiled");

        DumpOutputStream printer = new DumpOutputStream(output);

        DocumentComment docComment = entity.getDocComment();

        if (docComment != null) {
            printer.writeUTF(docComment.toString());
        } else {
            printer.writeUTF("");
        }

        printer.writeBoolean(entity.isStatic());

        // type
        printer.writeEnum(entity.getType());

        printer.writeBoolean(entity.isAbstract());
        printer.writeBoolean(entity.isFinal());

        // print name
        printer.writeName(entity.getName());
        printer.writeName(entity.getCompiledInternalName());

        printer.writeTrace(debugInformation ? entity.getTrace() : null);

        // parent
        ClassEntity parent = entity.getParent();
        if (entity.getDeferredParentName()!=null) {
            printer.writeName("\0deferred-parent:"+entity.getDeferredParentName());
        } else if (entity.getParent() != null){
            printer.writeName(parent.getName());
        } else {
            printer.writeName(null); // write null
        }

        // constants
        List<ConstantEntity> constants = new ArrayList<ConstantEntity>();
        for(ConstantEntity el : entity.getConstants()){
            if (el.isOwned(entity))
                constants.add(el);
        }

        printer.writeInt(constants.size());
        for(ConstantEntity el : constants){
            constantDumper.save(el, output);
        }

        // properties
        List<PropertyEntity> properties = new ArrayList<PropertyEntity>();
        for(PropertyEntity el : entity.getStaticProperties()){
            if (el.isOwned(entity))
                properties.add(el);
        }
        for(PropertyEntity el : entity.getProperties()){
            if (el.isOwned(entity))
                properties.add(el);
        }

        printer.writeInt(properties.size());
        for(PropertyEntity el : properties){
            propertyDumper.save(el, output);
        }

        // methods
        printer.writeInt(entity.getMethodCounts());

        List<MethodEntity> methods = entity.getOwnedMethods();
        printer.writeInt(methods.size());
        for (MethodEntity el : methods){
            methodDumper.save(el, output);
        }

        // interfaces
        printer.writeInt(entity.getInterfaces().size());
        for(ClassEntity el : entity.getInterfaces().values()){
            printer.writeName(el.getName());
        }

        // traits
        printer.writeInt(entity.getTraits().size());
        for(ClassEntity el : entity.getTraits().values()){
            printer.writeName(el.getName());
        }

        if (includeData) {
            printer.writeRawData(entity.getData(), Integer.MAX_VALUE);
        } else {
            printer.writeRawData(null);
        }

        // Preserve deferred compatibility checks for non-foldable trait values.
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        DumpOutputStream attributes = new DumpOutputStream(metadata, printer.isRawStringEnabled());
        attributes.writeInt(entity.isEnum()?9:entity.isPhpReadonly()?8:7);
        attributes.writeBoolean(entity.hasDeferredInitialization());
        attributes.writeBoolean(entity.isAnonymous());
        attributes.writeBoolean(entity.getDeferredParentName()!=null);
        attributes.writeInt(entity.getDeclarationId());
        writeRelations(attributes, entity.getDeclarationRelations());
        if(entity.isEnum()) attributes.writeBoolean(false);
        else if(entity.isPhpReadonly()) attributes.writeBoolean(true);
        attributes.writeInt(entity.getTraitConstantChecks().size());
        for (ClassEntity.TraitConstantCheck check : entity.getTraitConstantChecks()) {
            attributes.writeName(check.name);
            attributes.writeName(check.trait);
            attributes.writeMemory(check.value);
            attributes.writeName(check.owner);
        }
        if(entity.isEnum()) attributes.writeName(entity.getEnumBackingType());
        printer.writeRawData(metadata.toByteArray());
    }

    private void writeRelationName(DumpOutputStream out, PhpClassRelations.Name name) throws IOException {
        out.writeName(name.name());
        out.writeTrace(debugInformation ? name.trace() : null);
    }

    private void writeRelations(DumpOutputStream out, PhpClassRelations relations) throws IOException {
        out.writeBoolean(relations.parent() != null);
        if (relations.parent() != null) writeRelationName(out, relations.parent());
        out.writeInt(relations.interfaces().size());
        for (PhpClassRelations.InterfaceUse relation : relations.interfaces()) {
            out.writeByte(relation.kind() == PhpClassRelations.InterfaceKind.IMPLEMENTS ? 1 : 2);
            writeRelationName(out, relation.name());
        }
        out.writeInt(relations.traitGroups().size());
        for (PhpClassRelations.TraitGroup group : relations.traitGroups()) {
            out.writeTrace(debugInformation ? group.trace() : null);
            out.writeInt(group.traits().size());
            for (PhpClassRelations.Name name : group.traits()) writeRelationName(out, name);
            out.writeInt(group.adaptations().size());
            for (PhpClassRelations.Adaptation adaptation : group.adaptations()) {
                if (adaptation instanceof PhpClassRelations.Alias alias) {
                    out.writeByte(1);
                    out.writeTrace(debugInformation ? alias.trace() : null);
                    out.writeName(alias.trait());
                    out.writeName(alias.method());
                    out.writeName(alias.alias());
                    out.writeByte(alias.visibility() == null ? 0 : switch (alias.visibility()) {
                        case PUBLIC -> 1; case PROTECTED -> 2; case PRIVATE -> 3;
                    });
                    out.writeBoolean(alias.makeFinal());
                } else if (adaptation instanceof PhpClassRelations.Precedence precedence) {
                    out.writeByte(2);
                    out.writeTrace(debugInformation ? precedence.trace() : null);
                    out.writeName(precedence.preferredTrait());
                    out.writeName(precedence.method());
                    out.writeInt(precedence.excluded().size());
                    for (PhpClassRelations.Name name : precedence.excluded()) writeRelationName(out, name);
                } else throw new DumpException("Unknown trait adaptation");
            }
        }
    }

    private int relationCount(DumpInputStream in, int[] budget, boolean nonEmpty) throws IOException {
        int count = in.readInt();
        if (count < (nonEmpty ? 1 : 0) || count > 65535 || count > budget[0])
            throw new DumpException("Invalid declaration relation count");
        budget[0] -= count;
        return count;
    }

    private String relationName(DumpInputStream in, boolean nullable) throws IOException {
        String name = in.readName();
        if (name == null && nullable) return null;
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0)
            throw new DumpException("Invalid declaration relation name");
        return name;
    }

    private PhpClassRelations.Name readRelationName(DumpInputStream in) throws IOException {
        return new PhpClassRelations.Name(relationName(in, false), in.readTrace(context));
    }

    private PhpClassRelations readRelations(DumpInputStream in) throws IOException {
        int[] budget = {65535};
        PhpClassRelations.Name parent = in.readBoolean() ? readRelationName(in) : null;
        List<PhpClassRelations.InterfaceUse> interfaces = new ArrayList<>();
        int interfaceCount = relationCount(in, budget, false);
        for (int i = 0; i < interfaceCount; i++) {
            int kind = in.readUnsignedByte();
            if (kind != 1 && kind != 2) throw new DumpException("Invalid declaration interface kind");
            interfaces.add(new PhpClassRelations.InterfaceUse(readRelationName(in), kind == 1
                    ? PhpClassRelations.InterfaceKind.IMPLEMENTS : PhpClassRelations.InterfaceKind.EXTENDS));
        }
        List<PhpClassRelations.TraitGroup> groups = new ArrayList<>();
        int groupCount = relationCount(in, budget, false);
        for (int i = 0; i < groupCount; i++) {
            TraceInfo trace = in.readTrace(context);
            List<PhpClassRelations.Name> traits = new ArrayList<>();
            int traitCount = relationCount(in, budget, true);
            for (int j = 0; j < traitCount; j++) traits.add(readRelationName(in));
            List<PhpClassRelations.Adaptation> adaptations = new ArrayList<>();
            int adaptationCount = relationCount(in, budget, false);
            for (int j = 0; j < adaptationCount; j++) {
                int kind = in.readUnsignedByte();
                TraceInfo adaptationTrace = in.readTrace(context);
                if (kind == 1) {
                    String trait = relationName(in, true), method = relationName(in, false), alias = relationName(in, true);
                    Modifier visibility = switch (in.readUnsignedByte()) {
                        case 0 -> null; case 1 -> Modifier.PUBLIC; case 2 -> Modifier.PROTECTED; case 3 -> Modifier.PRIVATE;
                        default -> throw new DumpException("Invalid trait alias visibility");
                    };
                    adaptations.add(new PhpClassRelations.Alias(trait, method, alias, visibility, in.readBoolean(), adaptationTrace));
                } else if (kind == 2) {
                    String preferredTrait = relationName(in, false), method = relationName(in, false);
                    List<PhpClassRelations.Name> excluded = new ArrayList<>();
                    int count = relationCount(in, budget, true);
                    for (int k = 0; k < count; k++) excluded.add(readRelationName(in));
                    adaptations.add(new PhpClassRelations.Precedence(preferredTrait, method, excluded, adaptationTrace));
                } else throw new DumpException("Invalid trait adaptation kind");
            }
            groups.add(new PhpClassRelations.TraitGroup(traits, adaptations, trace));
        }
        return new PhpClassRelations(parent, interfaces, groups);
    }

    private record TraitCheckDeclaration(String name,String trait,php.runtime.Memory value,String owner) {}
    private record DeclarationMetadata(int version,boolean deferredInitialization,boolean anonymous,int declarationId,
            PhpClassRelations relations,boolean readonly,List<TraitCheckDeclaration> checks,String enumBackingType) {
        void applyHeader(ClassEntity entity) {
            if(version>=3)entity.setDeferredInitialization(deferredInitialization);
            if(version>=4)entity.setAnonymous(anonymous);
            if(version>=6 && declarationId>=0)entity.setDeclarationId(declarationId);
            if(version>=7 && declarationId>=0)entity.setDeclarationRelations(relations);
            if(version>=8)entity.setPhpReadonly(readonly);
            if(version>=9)entity.setEnumBackingType(enumBackingType);
        }
        void apply(ClassEntity entity) {
            applyHeader(entity);
            for(TraitCheckDeclaration check:checks)entity.addTraitConstantCheck(check.name,check.trait,check.value,check.owner);
        }
    }
    private DeclarationMetadata readDeclarationMetadata(ClassEntity entity,byte[] metadata,boolean rawStrings,
            List<PropertyEntity> ownProperties) throws IOException {
        if(metadata==null || metadata.length==0)return new DeclarationMetadata(0,false,false,-1,PhpClassRelations.EMPTY,false,List.of(),null);
        var attributes=new DumpInputStream(new ByteArrayInputStream(metadata),rawStrings);
        int version=attributes.readInt();
        if(version<1 || version>9)throw new DumpException("Unsupported class metadata version");
        boolean deferredInitialization=version>=3 && attributes.readBoolean();
        boolean anonymous=version>=4 && attributes.readBoolean();
        if(version>=5 && attributes.readBoolean()!=(entity.getDeferredParentName()!=null))
            throw new DumpException("Inconsistent deferred parent declaration metadata");
        if(version<5 && entity.getDeferredParentName()!=null)throw new DumpException("Deferred inheritance requires class metadata 5");
        int declarationId=version>=6?attributes.readInt():-1;
        if(declarationId < -1)throw new DumpException("Invalid deferred class declaration id");
        PhpClassRelations relations=version>=7?readRelations(attributes):PhpClassRelations.EMPTY;
        if(declarationId<0 && !relations.isEmpty())throw new DumpException("Relations require a deferred class declaration");
        if(declarationId>=0) {
            String relationParent=relations.parent()==null?null:relations.parent().name();
            if(!java.util.Objects.equals(relationParent,entity.getDeferredParentName()))throw new DumpException("Inconsistent deferred parent recipe");
        }
        boolean readonly=false;
        if(version>=8) {
            int flag=attributes.readUnsignedByte();
            if(flag>1 || version==8 && flag!=1)throw new DumpException("Invalid readonly class flag");
            readonly=flag==1;
            if(readonly && entity.getType()!=ClassEntity.Type.CLASS)throw new DumpException("Invalid readonly class metadata kind");
            for(PropertyEntity property:readonly?ownProperties:java.util.List.<PropertyEntity>of()) {
                if(property.isStatic())throw new DumpException("Readonly class contains static property metadata");
                if(!property.isPhpReadonly() || property.getDeclaredType()==null || property.isDefault())
                    throw new DumpException("Invalid readonly class property metadata");
            }
        }
        int count=attributes.readInt();
        if(count<0 || count>65535)throw new DumpException("Invalid trait constant check count");
        List<TraitCheckDeclaration> checks=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String name=attributes.readName(),trait=attributes.readName();php.runtime.Memory value=attributes.readMemory();
            checks.add(new TraitCheckDeclaration(name,trait,value,version>=2?attributes.readName():null));
        }
        String enumBackingType=version>=9?attributes.readName():null;
        if(version>=9 && (!entity.isEnum() || enumBackingType!=null && !enumBackingType.equals("int") && !enumBackingType.equals("string")))throw new DumpException("Invalid enum backing declaration");
        if(entity.isEnum() && (version<9 || module==null || module.getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5 || readonly || anonymous || entity.isAbstract() || !entity.isFinal()))throw new DumpException("Invalid enum class declaration");
        if(attributes.available()!=0)throw new DumpException("Invalid class metadata size");
        return new DeclarationMetadata(version,deferredInitialization,anonymous,declarationId,relations,readonly,checks,enumBackingType);
    }

    @Override
    public ClassEntity load(InputStream input)  throws IOException {
        return load(input, ClassEntity.class);
    }

    public <T extends ClassEntity> T load(InputStream input, Class<T> clazz) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        T entity   = null;
        try {
            entity = clazz.getConstructor(Context.class).newInstance(context);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        entity.setId(env.scope.nextClassIndex());
        entity.setModule(module);

        String docComment = data.readUTF();

        if (!docComment.isEmpty()) {
            entity.setDocComment(new DocumentComment(docComment));
        }

        entity.setStatic(data.readBoolean());
        entity.setType(data.readClassType());
        entity.setAbstract(data.readBoolean());
        entity.setFinal(data.readBoolean());
        entity.setName(data.readName());
        entity.setInternalName(data.readName());
        entity.setTrace(data.readTrace(context));

        // parent
        String parent = data.readName();
        if (parent!=null && parent.startsWith("\0deferred-parent:")) {
            String pending=parent.substring("\0deferred-parent:".length());
            if (pending.isEmpty()) throw new DumpException("Empty deferred parent declaration");
            entity.setDeferredParentName(pending);parent=null;
        }

        // Decode declaration data before consulting a parent provider. Do not link owned members yet.
        int constantCount = data.readInt();
        if(constantCount<0 || constantCount>65535) throw new DumpException("Invalid class constant count");
        List<ConstantEntity> ownConstants = new ArrayList<>();
        for(int i=0;i<constantCount;i++) ownConstants.add(constantDumper.load(input));
        int propertyCount = data.readInt();
        if(propertyCount<0 || propertyCount>65535) throw new DumpException("Invalid class property count");
        List<PropertyEntity> ownProperties = new ArrayList<>();
        for(int i=0;i<propertyCount;i++) ownProperties.add(propertyDumper.load(input));
        int savedMethodCounts = data.readInt();
        if(savedMethodCounts<0) throw new DumpException("Invalid saved method count");
        int methodCount = data.readInt();
        if(methodCount<0 || methodCount>65535) throw new DumpException("Invalid class method count");
        List<MethodEntity> ownMethods = new ArrayList<>();
        for(int i=0;i<methodCount;i++) ownMethods.add(methodDumper.load(input));

        List<String> interfaceNames = new ArrayList<>(), traitNames = new ArrayList<>();
        int interfaceCount = data.readInt();
        if (interfaceCount < 0 || interfaceCount > 65535) throw new DumpException("Invalid interface count");
        for (int i = 0; i < interfaceCount; i++) interfaceNames.add(data.readName());
        int traitCount = data.readInt();
        if (traitCount < 0 || traitCount > 65535) throw new DumpException("Invalid trait count");
        for (int i = 0; i < traitCount; i++) traitNames.add(data.readName());

        entity.setData(data.readRawData(Integer.MAX_VALUE));
        byte[] metadata = data.readRawData();
        DeclarationMetadata declaration = readDeclarationMetadata(entity,metadata,data.isRawStringEnabled(),ownProperties);
        if(declaration.declarationId>=0 && (!interfaceNames.isEmpty() || !traitNames.isEmpty()))
            throw new DumpException("Cold declaration contains composed relations");

        declaration.applyHeader(entity);
        java.util.Set<String> enumCaseNames=new java.util.HashSet<>();
        for(ConstantEntity constant:ownConstants) {
            if(constant.isEnumCase()) {
                if(!entity.isEnum() || !enumCaseNames.add(constant.getName()) || !constant.isPublic() || constant.getDeclaredType()!=null || constant.getTraitName()!=null || constant.isFinal())throw new DumpException("Invalid enum case metadata");
                if(entity.getEnumBackingType()==null && (constant.getStoredValue()==null || !constant.getStoredValue().isNull() || constant.getInitializerMethod()!=null))throw new DumpException("Unit enum case contains backing initializer");
                if(entity.getEnumBackingType()!=null && constant.getStoredValue()==null && constant.getInitializerMethod()==null)throw new DumpException("Backed enum case lacks initializer");
                if(constant.getStoredValue()!=null && (constant.getStoredValue().isObject() || constant.getStoredValue().isArray()))throw new DumpException("Enum contains embedded heap backing value");
            }
        }
        if(entity.isEnum()) {
            if(parent!=null || entity.getDeferredParentName()!=null)throw new DumpException("Enum payload extends a class");
            for(PropertyEntity property:ownProperties){property.setClazz(entity);if(!php.runtime.PhpEnums.syntheticProperty(entity,property))throw new DumpException("Enum payload contains ordinary property");}
            for(MethodEntity method:ownMethods){method.setClazz(entity);if(method.getName().equalsIgnoreCase("__construct"))throw new DumpException("Enum payload contains constructor");}
        }
        PhpEnumMetadata.validate(entity,ownConstants,ownProperties,ownMethods);
        PhpPropertyHookDumper.validateDecodedClass(entity, ownProperties, ownMethods, parent!=null,env);
        final T decoded=entity;final String parentName=parent;
        RelationLink relations=()-> {
            decoded.constants.clear();decoded.properties.clear();decoded.staticProperties.clear();decoded.getMethods().clear();
        // Retain the historical valid-link order: parentName first, then every owned member and prototype.
        if (parentName != null){
            ClassEntity parentEntity = module == null ? null : module.findClass(parentName);
            if (parentEntity == null) {
                parentEntity = env.fetchClass(parentName, true, module == null ? env.getScope().getPhpLanguageProfile() : module.getPhpLanguageProfile());
            }

            if (parentEntity == null) {
                env.error(env.trace(), ErrorType.E_ERROR, Messages.ERR_CLASS_NOT_FOUND, parentName);
            } else {
                ClassEntity.ExtendsResult result = decoded.setParent(parentEntity, false);

                if (clazz != GeneratorEntity.class)
                    result.check(env);
            }
        }

        for(ConstantEntity el:ownConstants) {el.setClazz(decoded);decoded.addConstant(el).check(env);}
        for(PropertyEntity el:ownProperties) {el.setClazz(decoded);decoded.addProperty(el).check(env);}
        decoded.__setMethodCounts(savedMethodCounts);
        for(MethodEntity el:ownMethods) {el.setClazz(decoded);decoded.addMethod(el,null).check(env);}
        decoded.updateParentBody().check(env);
        declaration.apply(decoded);

        for (String name : interfaceNames) {
            ClassEntity interfaceEntity = module == null ? null : module.findClass(name);
            if (interfaceEntity == null)
                interfaceEntity = env.fetchClass(name, true, module == null ? env.getScope().getPhpLanguageProfile() : module.getPhpLanguageProfile());

            if (interfaceEntity == null)
                env.error(env.trace(), ErrorType.E_ERROR, Messages.ERR_INTERFACE_NOT_FOUND, name);

            ClassEntity.ImplementsResult result = decoded.addInterface(interfaceEntity);
            if (!decoded.isUnresolvedDeclaration()) result.check(env);
        }


        for (String name : traitNames) {
            ClassEntity traitEntity = module == null ? null : module.findClass(name);
            if (traitEntity == null)
                traitEntity = env.fetchClass(name, true, module == null ? env.getScope().getPhpLanguageProfile() : module.getPhpLanguageProfile());

            if (traitEntity == null)
                env.error(env.trace(), ErrorType.E_ERROR, Messages.ERR_TRAIT_NOT_FOUND, name);
            else if (!traitEntity.isTrait())
                env.error(env.trace(), ErrorType.E_ERROR, Messages.ERR_CANNOT_USE_NON_TRAIT, name);

            decoded.addTrait(traitEntity);
        }

        if(decoded.isEnum()) php.runtime.PhpEnums.validateDeclaration(decoded,env);
        decoded.doneDeclare();
        php.runtime.reflection.support.PhpOverrideValidation.validate(decoded,env);
        };
        if(deferLinking) {
            declaration.applyHeader(entity);
            for(ConstantEntity constant:ownConstants) {constant.setClazz(entity);entity.constants.put(constant.getName(),constant);}
            for(PropertyEntity property:ownProperties) {property.setClazz(entity);(property.isStatic()?entity.staticProperties:entity.properties).put(property.getName(),property);}
            for(MethodEntity method:ownMethods) {method.setClazz(entity);entity.getMethods().put(method.getLowerName(),method);}
            entity.__setMethodCounts(savedMethodCounts);
            deferredLinks.add(relations);
        } else relations.link();
        return entity;
    }
}
