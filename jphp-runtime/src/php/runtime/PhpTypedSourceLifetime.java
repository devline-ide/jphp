package php.runtime;

import java.util.*;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ClassEntity;

/** Sparse EVAL/CV custody for an independently verified, nonescaping typed object interval. */
public final class PhpTypedSourceLifetime {
    private PhpTypedSourceLifetime() {}
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final class Facet {
        final PhpTypedSourcePlan.Site site;
        final IObject object;
        final ArrayMemory properties;
        final ClassEntity declaration;
        ReferenceMemory cv;
        Facet(PhpTypedSourcePlan.Site site, IObject object, ArrayMemory properties) {
            this.site=site;this.object=object;this.properties=properties;this.declaration=object.getReflection();
        }
        boolean hasExactProperties() {
            return object instanceof BaseObject && ((BaseObject)object).peekProperties()==properties
                    && object.getReflection()==declaration && declaration.getNativeClass()==object.getClass();
        }
        void releaseSources() {
            if (!hasExactProperties()) return;
            for (var property:declaration.getProperties()) {
                if (property.isStatic() || property.getDeclaredType()==null) continue;
                ReferenceMemory carrier=properties.getByScalar(property.getSpecificName());
                if (carrier!=null) carrier.detachPropertySource();
            }
        }
    }
    private static final class Session {
        final IdentityHashMap<PhpPropertyWriteContext.Frame,Map<PhpTypedSourcePlan.Site,Facet>> scopes=new IdentityHashMap<>();
    }
    /** Eligibility depends on the actual generated declaration and stored values, never PHP names. */
    public static boolean eligible(ClassEntity declaration) {
        if (declaration==null || declaration.isInternal() || declaration.getParent()!=null
                || declaration.methodConstruct!=null || declaration.methodDestruct!=null
                || !declaration.getMethods().isEmpty() || declaration.getData()==null) return false;
        boolean typed=false;
        for (var property:declaration.getProperties()) {
            if (property.isStatic()) continue;
            if (property.getGetter()!=null || property.getSetter()!=null) return false;
            Memory value=property.getDefaultValue();
            if (value!=null && (value.isArray() || value.isObject() || value.isReference())) return false;
            typed|=property.getDeclaredType()!=null;
        }
        return typed;
    }
    public static void created(Memory actual,Class<?> declaration,Class<?> owner,String member,String descriptor,int id,int local) {
        var context=PhpPropertyWriteContext.current(null);
        if (context==null || PhpOwnershipExecution.current(context.environment)!=null
                || PhpRuntimeOwnership.current(context.environment)!=null) return;
        var site=PhpTypedSourcePlan.require(owner,member,descriptor,id,local,"created");
        if (site==null || !(actual instanceof ObjectMemory)) return;
        IObject object=((ObjectMemory)actual).value;
        if (object==null || object.getClass()!=declaration || site.declaration()!=declaration
                || !eligible(object.getReflection()) || !(object instanceof BaseObject)) return;
        var table=((BaseObject)object).peekProperties();if(table==null)return;
        // A declared scalar/CELL fetch may not publish an object/array hidden in a stored default.
        for(String key:site.properties()) {
            var property=object.getReflection().findProperty(key);
            if(property==null || property.isStatic() || property.getDeclaredType()==null || !property.isPublic())return;
            var cell=table.getByScalar(property.getSpecificName());
            if(cell==null || cell.toValue().isArray() || cell.toValue().isObject())return;
        }
        Session session=CURRENT.get();if(session==null){session=new Session();CURRENT.set(session);}
        var scope=session.scopes.computeIfAbsent(context,ignored->new IdentityHashMap<>());
        // Reentry cannot reuse an unfinished producer. Abandon both proofs rather than retiring a live Source.
        if(scope.containsKey(site)){scope.remove(site);return;}
        scope.put(site,new Facet(site,object,table));
    }
    public static void published(Memory actual,Class<?> declaration,Class<?> owner,String member,String descriptor,int id,int local) {
        var site=PhpTypedSourcePlan.require(owner,member,descriptor,id,local,"published");
        var facet=take(site,false);if(facet==null)return;
        if(!(actual instanceof ReferenceMemory) || !((ReferenceMemory)actual).isExclusiveTypedObjectCv(facet.object)
                || !facet.hasExactProperties()){take(site,true);return;}
        facet.cv=(ReferenceMemory)actual;
    }
    public static void released(Memory actual,Class<?> declaration,Class<?> owner,String member,String descriptor,int id,int local) {
        var site=PhpTypedSourcePlan.require(owner,member,descriptor,id,local,"released");
        var facet=take(site,true);if(facet==null)return;
        // The actual physical CV was erased by the original unset operation before this observer runs.
        if(actual!=facet.cv || facet.cv==null || !facet.cv.getValue().isUndefined())return;
        facet.releaseSources();
    }
    private static Facet take(PhpTypedSourcePlan.Site site,boolean remove) {
        Session session=CURRENT.get();var context=PhpPropertyWriteContext.current(null);
        if(session==null || context==null || site==null)return null;
        var scope=session.scopes.get(context);if(scope==null)return null;
        Facet facet=remove?scope.remove(site):scope.get(site);
        if(remove && scope.isEmpty()){session.scopes.remove(context);if(session.scopes.isEmpty())CURRENT.remove();}
        return facet;
    }
    /** Exceptional/early exits abandon proof; they do not pretend the CV or an escaped object died. */
    public static void leave(PhpPropertyWriteContext.Frame frame) {
        Session session=CURRENT.get();if(session==null)return;
        session.scopes.remove(frame);if(session.scopes.isEmpty())CURRENT.remove();
    }
}
