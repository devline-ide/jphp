package php.runtime;
import php.runtime.env.*;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpPropertyHooks;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.PropertyCallCache;
/** No ThreadLocal, receiver cache or per-access token. Hook metadata is immutable and body operands are explicit. */
public final class PhpPropertyHookAccess {
 private PhpPropertyHookAccess(){}
 private static boolean backing(PropertyEntity property,Environment env,IObject receiver,PhpPropertyHooks.Hook body,IObject original){
  return PhpPropertyHookBodyAccess.isBackingAccess(body,original,env,receiver,property);
 }
 private static Memory invoke(PhpPropertyHooks.Hook hook,Environment env,TraceInfo trace,IObject receiver,Memory... args)throws Throwable{
  hook.validate();
  if(hook.declaration().getTrait()!=null && (PhpOwnershipExecution.current(env)!=null || env.getRuntimeOwnership()!=null))
   php.runtime.loader.dump.PhpTraitPropertyHookOrigin.requireOwnershipAdmission(hook,env);
  return ObjectInvokeHelper.invokeMethod(receiver,hook.method(),env,trace==null?TraceInfo.UNKNOWN:trace,args,false);
 }
 public static void error(Environment env,TraceInfo trace,String text,PropertyEntity property){
  env.exception(trace,BaseError.class,text,property.getClazz().getName(),property.getName());
 }
 /** Java null is only backed ordinary fallback; actual PHP null remains Memory.NULL. */
 public static Memory read(PropertyEntity property,Environment env,TraceInfo trace,IObject receiver,boolean reference,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  var hooks=property.getPhpHooks();if(hooks==null)return null;
  if(backing(property,env,receiver,body,original)){
   if(hooks.isVirtual())error(env,trace,"Must not read from virtual property %s::$%s",property);return null;
  }
  var getter=hooks.get();
  if(getter==null){if(hooks.isVirtual())error(env,trace,"Property %s::$%s is write-only",property);return null;}
  if(reference&&!getter.method().isReturnReference())error(env,trace,"Indirect modification of %s::$%s is not allowed",property);
  Memory result=invoke(getter,env,trace,receiver);
  if(reference&&!(result instanceof ReferenceMemory))throw new IllegalArgumentException("Reference hook lacks its actual returned CELL");
  return result;
 }
 public static boolean write(PropertyEntity property,Environment env,TraceInfo trace,IObject receiver,Memory value,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  var hooks=property.getPhpHooks();if(hooks==null)return false;
  if(backing(property,env,receiver,body,original)){
   if(hooks.isVirtual())error(env,trace,"Must not write to virtual property %s::$%s",property);return false;
  }
  if(hooks.set()==null){if(hooks.isVirtual())error(env,trace,"Property %s::$%s is read-only",property);return false;}
  invoke(hooks.set(),env,trace,receiver,value);return true;
 }
 public static Memory test(PropertyEntity property,Environment env,TraceInfo trace,IObject receiver,boolean empty,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  var hooks=property.getPhpHooks();if(hooks==null||backing(property,env,receiver,body,original))return null;
  if(hooks.get()==null)return hooks.isVirtual()?Memory.NULL:null;
  Memory value=read(property,env,trace,receiver,false,null,null);
  return empty?(value.toBoolean()?Memory.TRUE:Memory.NULL):(value.isNull()?Memory.NULL:Memory.TRUE);
 }
 public static void beforeUnset(PropertyEntity property,Environment env,TraceInfo trace,IObject receiver,PhpPropertyHooks.Hook body,IObject original){
  if(property.getPhpHooks()!=null&&!backing(property,env,receiver,body,original))error(env,trace,"Cannot unset hooked property %s::$%s",property);
 }

 /** Consumer view only: raw cast/serialization continues to use the object's backing storage. */
 public static php.runtime.lang.ForeachIterator consumerProperties(IObject receiver,Environment env,TraceInfo trace,ClassEntity context,int visibility){
  final IObject actualReceiver=php.runtime.PhpLazyObjects.observeUnchecked(env,receiver,php.runtime.PhpLazyObjects.Observation.PROPERTIES);
  if(actualReceiver instanceof php.runtime.lang.support.IPhpNativeDateView date && visibility!=2)return date.phpDateExport(env).foreachIterator(false,false);
  if(actualReceiver instanceof php.runtime.lang.spl.iterator.ArrayObject && visibility!=2)
   return ((php.runtime.lang.spl.iterator.ArrayObject)actualReceiver).castArray(env).toValue(ArrayMemory.class).foreachIterator(false,false);
  ClassEntity entity=actualReceiver.getReflection();boolean hooked=false;
  for(ClassEntity owner=entity;owner!=null&&!hooked;owner=owner.getParent())for(PropertyEntity property:owner.getProperties())if(property.getPhpHooks()!=null){hooked=true;break;}
  ArrayMemory storage=actualReceiver.getProperties();if(!hooked)return storage.foreachIterator(false,false);
  if(visibility<0||visibility>2)throw new IllegalArgumentException("Unknown property consumer visibility");
  java.util.Deque<ClassEntity> lineage=new java.util.ArrayDeque<>();for(ClassEntity owner=entity;owner!=null;owner=owner.getParent())lineage.push(owner);
  java.util.LinkedHashMap<String,PropertyEntity> positions=new java.util.LinkedHashMap<>();
  while(!lineage.isEmpty()){
   ClassEntity owner=lineage.pop();
   for(PropertyEntity declared:owner.getProperties()){
    if(declared.isStatic()||declared.getClazz()!=owner)continue;
    PropertyEntity property=declared.isPrivate()?declared:entity.findProperty(declared.getName());
    if(property!=null&&!property.isStatic())positions.putIfAbsent(property.getSpecificName(),property);
   }
  }
  var raw=storage.foreachIterator(false,false);while(raw.next())positions.putIfAbsent(raw.getKey().toString(),null);
  // Snapshot positions before callbacks; values are fetched lazily so later mutations remain observable.
  var keys=java.util.List.copyOf(positions.keySet());
  return new php.runtime.lang.ForeachIterator(false,false,false){
   private int position;
   @Override protected boolean init(){reset();return true;}
   @Override public void reset(){position=0;currentKey=null;currentValue=null;currentKeyMemory=null;}
   @Override protected boolean prevValue(){return false;}
   @Override protected boolean nextValue(){
    while(position<keys.size()){
     String key=keys.get(position++);PropertyEntity property=positions.get(key);
     if(visibility==1&&(property!=null?!property.isPublic():key.startsWith("\0")))continue;
     if(visibility==2&&property!=null&&property.canAccess(env,context)!=0)continue;
     Memory value;var hooks=property==null?null:property.getPhpHooks();
     if(hooks!=null&&hooks.get()!=null){
      try{value=read(property,env,trace,actualReceiver,false,null,null);}catch(Throwable failure){return PhpPropertyHookAccess.<Boolean,RuntimeException>consumerFailure(failure);}
     }else{
      if(hooks!=null&&hooks.isVirtual())continue;
      ReferenceMemory entry=storage.getByScalar(key);if(entry==null)continue;value=entry.getValue();
     }
     if(value.isUndefined())continue;currentKey=key;currentKeyMemory=null;currentValue=value;return true;
    }
    currentKey=null;currentValue=null;return false;
   }
  };
 }
 @SuppressWarnings("unchecked") private static <R,T extends Throwable> R consumerFailure(Throwable failure)throws T{throw (T)failure;}
 private static IObject object(Memory value){return value.toValue(ObjectMemory.class).value;}
 public static Memory getProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.getProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().getHookBodyProperty(env,trace,actual,property,cache,index,false,false,body,original);
 }
 public static Memory getObjectWriteReceiverProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.getObjectWriteReceiverProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().getObjectWriteReceiverProperty(env,trace,actual,property,cache,index,body,original);
 }
 public static Memory getRefProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.getRefProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().getHookBodyProperty(env,trace,actual,property,cache,index,true,false,body,original);
 }
 public static Memory getQuietArrayUnsetProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,
   PhpPropertyHooks.Hook body,IObject original)throws Throwable {
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.getQuietArrayUnsetProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().getQuietArrayUnsetProperty(env,trace,actual,property,cache,index,body,original);
 }
 public static Memory getQuietProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.getQuietProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().getHookBodyProperty(env,trace,actual,property,cache,index,false,true,body,original);
 }
 public static Memory assignProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,null,cache,index,body,original);
 }
 public static Memory assignProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,String operator,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return PhpArithmetic.assignProperty(receiver,value,property,env,trace,cache,index,operator);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return PhpArithmetic.binary(left,right,operator,env,trace);}
  },cache,index,body,original);
 }
 public static Memory incrementProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,boolean decrement,boolean post,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return PhpArithmetic.incrementProperty(receiver,property,env,trace,cache,index,decrement,post);
  IObject actual=object(receiver);var declared=actual.getReflection().findProperty(property);
  if(declared==null||declared.getPhpHooks()==null)return PhpArithmetic.incrementProperty(receiver,property,env,trace,cache,index,decrement,post);
  Memory result=actual.getReflection().getHookBodyProperty(env,trace,actual,property,cache,index,false,false,body,original);
  ReferenceMemory scratch=new ReferenceMemory(result.toImmutable());
  return PhpArithmetic.incrementReference(scratch,decrement,post,env,trace,updated->{try{actual.getReflection().setProperty(env,trace,actual,property,updated,null,cache,index,body,original);}catch(Throwable failure){env.forwardThrow(failure);}});
 }
 public static Memory issetProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.issetProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().issetProperty(env,trace,actual,property,cache,index,body,original);
 }
 public static Memory emptyProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.emptyProperty(receiver,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().emptyProperty(env,trace,actual,property,body,original);
 }
 public static void unsetProperty(Memory receiver,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject()){ObjectInvokeHelper.unsetProperty(receiver,property,env,trace,cache,index);return;}
  IObject actual=object(receiver);actual.getReflection().unsetProperty(env,trace,actual,property,cache,index,body,original);
 }
 public static Memory assignConcatProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignConcatProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return PhpByteStringValues.concatPropertyValue(left,right,env,trace);}
  },cache,index,body,original);
 }
 public static Memory assignBitAndProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,php.runtime.reflection.support.PhpPropertyHooks.Hook body,php.runtime.lang.IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignBitAndProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return left.bitAnd(right);}
  },cache,index,body,original);
 }
 public static Memory assignBitOrProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,php.runtime.reflection.support.PhpPropertyHooks.Hook body,php.runtime.lang.IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignBitOrProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return left.bitOr(right);}
  },cache,index,body,original);
 }
 public static Memory assignBitXorProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,php.runtime.reflection.support.PhpPropertyHooks.Hook body,php.runtime.lang.IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignBitXorProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return left.bitXor(right);}
  },cache,index,body,original);
 }
 public static Memory assignBitShrProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,php.runtime.reflection.support.PhpPropertyHooks.Hook body,php.runtime.lang.IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignBitShrProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return left.bitShr(right);}
  },cache,index,body,original);
 }
 public static Memory assignBitShlProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,php.runtime.reflection.support.PhpPropertyHooks.Hook body,php.runtime.lang.IObject original)throws Throwable{
  if(!receiver.toValue().isObject())return ObjectInvokeHelper.assignBitShlProperty(receiver,value,property,env,trace,cache,index);
  IObject actual=object(receiver);return actual.getReflection().setProperty(env,trace,actual,property,value,new ClassEntity.SetterCallback(){
   @Override public Memory invoke(Memory left,Memory right){return left.bitShl(right);}
  },cache,index,body,original);
 }
 public static Memory assignRefProperty(Memory receiver,Memory value,String property,Environment env,TraceInfo trace,PropertyCallCache cache,int index,PhpPropertyHooks.Hook body,IObject original)throws Throwable{
  return getRefProperty(receiver,property,env,trace,cache,index,body,original).assignRef(value);
 }
}
