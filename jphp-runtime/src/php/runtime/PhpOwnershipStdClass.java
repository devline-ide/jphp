package php.runtime;

import php.runtime.env.Environment;
import php.runtime.lang.StdClass;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.ClassEntity;
import java.lang.reflect.Constructor;
import java.util.*;

/** The exact built-in shell and its physical TABLE children; no arbitrary object factory is inferred. */
public final class PhpOwnershipStdClass {
    private PhpOwnershipStdClass() {}
    public static Plan prepare(Environment environment) {return new Plan(environment);}
    public static final class Plan {
        private final Environment environment;
        private final ClassEntity entity;
        private final Constructor<?> constructor;
        private final IdentityHashMap<PhpOwnershipObjects,PhpOwnershipObjects.Provider> providers=new IdentityHashMap<>();
        private Plan(Environment environment) {
            this.environment=Objects.requireNonNull(environment);entity=environment.fetchClass(StdClass.class);
            try {constructor=StdClass.class.getConstructor(Environment.class,ClassEntity.class);}
            catch(NoSuchMethodException absent){throw new IllegalArgumentException("Built-in stdClass constructor disappeared",absent);}
            validate();
        }
        public void validate() {
            if(environment.fetchClass(StdClass.class)!=entity || !"stdClass".equals(entity.getName())
                    || entity.getNativeClass()!=StdClass.class || !constructor.equals(entity.getOwnershipNativeConstructor())
                    || !entity.isClass() || entity.isAbstract() || entity.getParent()!=null
                    || !entity.getProperties().isEmpty() || !entity.getMethods().isEmpty()
                    || !entity.getTraits().isEmpty() || !entity.getInterfaces().isEmpty()
                    || entity.methodConstruct!=null || entity.methodDestruct!=null)
                throw new IllegalArgumentException("Built-in stdClass shell identity or empty PHP descriptor changed");
        }
        private PhpOwnershipObjects.Provider provider(PhpOwnershipObjects objects) {
            validate();var provider=providers.get(objects);
            if(provider==null) {
                provider=objects.registerPropertiesProvider(entity,constructor,(selected,env)->{
                    if(selected!=entity || env!=environment)throw new IllegalArgumentException("stdClass provider changed its class or Environment");
                    validate();
                });providers.put(objects,provider);
            }
            return provider;
        }
        private record Child(String key,ReferenceMemory carrier,PhpRuntimeOwnership.Holder holder,Memory raw,
                             PhpRuntimeOwnership.Role role,PhpOwnershipValues.Binding binding) {
            void validate() {
                if(!holder.matches(carrier) || holder.raw()!=raw || holder.role()!=role)
                    throw new IllegalArgumentException("ARRAY cast child changed its actual physical publisher");
            }
        }
        /** Explicit producer transfer, used only after the caller has attested its cast site. */
        public Memory cast(PhpRuntimeOwnership.Frame frame,int resultId,int sourceId,Memory actual)throws Throwable {
            Objects.requireNonNull(frame);var authority=frame.authority();validate();
            if(authority.environment()!=environment || authority.currentFrame()!=frame || resultId==sourceId)
                throw new IllegalArgumentException("stdClass cast changed its actual producer frame or result");
            frame.requireAvailableAllocationEvaluation(resultId);
            var source=frame.evalHolder(sourceId);
            if(source==null)throw new IllegalArgumentException("stdClass cast has no actual ARRAY producer");
            source.requireProducerEvaluation();
            if(source.role()!=PhpRuntimeOwnership.Role.VALUE || actual==null || actual.getClass()!=ArrayMemory.class)
                throw new IllegalArgumentException("stdClass cast requires the exact ARRAY VALUE producer");
            var array=(ArrayMemory)actual;var origin=array.getRuntimeOrigin();
            if(origin==null || origin.authority().domain()!=authority.domain() || authority.value(array,PhpRuntimeOwnership.Role.VALUE).node()!=source.node())
                throw new IllegalArgumentException("stdClass cast ARRAY differs from its producer/storage origin");
            origin.validate(array);List<Child> children=new ArrayList<>();
            for(Object key:array.keySet()) {
                if(!(key instanceof String) && !(key instanceof LongMemory))throw new IllegalArgumentException("ARRAY cast key lacks its physical scalar representation");
                var carrier=array.getByScalar(key);var holder=carrier==null?null:carrier.getRuntimeHolder();
                if(holder==null || holder.domain()!=authority.domain() || !holder.matches(carrier))throw new IllegalArgumentException("ARRAY cast bucket lacks its actual publisher");
                Memory raw=holder.raw();var role=holder.role();
                if(role!=PhpRuntimeOwnership.Role.VALUE && role!=PhpRuntimeOwnership.Role.CELL)throw new IllegalArgumentException("ARRAY cast child is not VALUE/CELL");
                Memory copied=role==PhpRuntimeOwnership.Role.CELL?raw:raw.toImmutable();var value=authority.value(copied,role);
                var binding=role==PhpRuntimeOwnership.Role.CELL?PhpOwnershipValues.bindCell(authority.domain(),(ReferenceMemory)copied,value.node()):PhpOwnershipValues.bindValue(authority.domain(),copied,value.node());
                children.add(new Child(key.toString(),carrier,holder,raw,role,binding));
            }
            for(Child child:children)child.validate();
            var objects=authority.objects();var provider=provider(objects);boolean staged=false;
            try(var allocation=objects.allocate(provider,environment,Map.of())) {
                Memory result=frame.stageAllocation(resultId,allocation);staged=true;
                var object=(ObjectMemory)result;var table=allocationTable(object);
                for(Child child:children) {
                    child.validate();var slot=table.insertBucket(child.key(),child.binding());
                    authority.adopt(slot.ownerPublisher().runtimeFacet());
                }
                frame.releaseEval(sourceId);return result;
            }catch(Throwable failure) {
                if(staged)try{frame.releaseEval(resultId);}catch(Throwable cleanup){failure=authority.domain().graph().composeFailure(failure,cleanup);}
                throw failure;
            }
        }
        private static PhpOwnershipObjects.PropertiesCertificate allocationTable(ObjectMemory object) {
            return ((StdClass)object.value).getOwnershipObject().properties();
        }
    }
}
