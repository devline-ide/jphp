package org.develnext.jphp.yaml.classes;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.error.YAMLException;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.annotation.Reflection.Arg;
import php.runtime.annotation.Reflection.Name;
import php.runtime.annotation.Reflection.Optional;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.env.Environment;
import php.runtime.ext.core.classes.WrapJavaExceptions;
import php.runtime.ext.core.classes.format.WrapProcessor;
import php.runtime.ext.core.classes.stream.Stream;
import php.runtime.memory.StringMemory;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.ClassEntity;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.*;

@Name("php\\format\\YamlProcessor")
public class YamlProcessor extends WrapProcessor {
    public static final int SERIALIZE_PRETTY_FLOW = 1;
    public static final int SERIALIZE_CANONICAL = 2;
    public static final int SERIALIZE_EXPLICIT_START = 4;
    public static final int SERIALIZE_EXPLICIT_END = 8;
    public static final int SERIALIZE_NOT_SPLIT_LINES = 16;
    public static final int SERIALIZE_FLOW = 32;
    public static final int SERIALIZE_BLOCK = 64;
    /** Section mappings, bounded aliases/nesting, unique keys, scalar/array values only. */
    public static final int STORAGE = 1024;

    private Yaml yaml;
    private boolean storage;

    public YamlProcessor(Environment env, Yaml yaml) {
        super(env);
        this.yaml = yaml;
    }

    public YamlProcessor(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }


    @Signature(@Arg(value = "flags", optional = @Optional("0")))
    public Memory __construct(Environment env, Memory... args) {
        DumperOptions options = new DumperOptions();

        int flags = args[0].toInteger();

        options.setPrettyFlow((flags & SERIALIZE_PRETTY_FLOW) == SERIALIZE_PRETTY_FLOW);
        options.setCanonical((flags & SERIALIZE_CANONICAL) == SERIALIZE_CANONICAL);
        options.setExplicitStart((flags & SERIALIZE_EXPLICIT_START) == SERIALIZE_EXPLICIT_START);
        options.setExplicitEnd((flags & SERIALIZE_EXPLICIT_END) == SERIALIZE_EXPLICIT_END);
        options.setSplitLines(!((flags & SERIALIZE_NOT_SPLIT_LINES) == SERIALIZE_NOT_SPLIT_LINES));

        if ((flags & SERIALIZE_FLOW) != 0) options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        if ((flags & SERIALIZE_BLOCK) != 0) options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        storage = (flags & STORAGE) != 0;
        if (storage) {
            LoaderOptions loader = new LoaderOptions();
            loader.setAllowDuplicateKeys(false);
            loader.setNestingDepthLimit(128);
            loader.setCodePointLimit(8 * 1024 * 1024);
            Resolver resolver = new Resolver() {
                @Override public Tag resolve(NodeId kind, String value, boolean implicit) {
                    Tag tag = super.resolve(kind, value, implicit);
                    return Tag.TIMESTAMP.equals(tag) ? Tag.STR : tag;
                }
            };
            yaml = new Yaml(new SafeConstructor(loader), new Representer(options), options, loader, resolver);
        } else {
            yaml = new Yaml(options);
        }
        return Memory.NULL;
    }

    @Override
    @Signature
    public Memory parse(Environment env, Memory... args) {
        try {
            Object value = args[0].instanceOf(Stream.class) ? yaml.load(Stream.getInputStream(env, args[0])) : yaml.load(args[0].toString());
            if (storage) {
                if (!(value instanceof Map) || ((Map<?, ?>) value).values().stream().anyMatch(it -> !(it instanceof Map)))
                    throw new YAMLException("YAML storage root and sections must be mappings");
                value = storageValue(value, 0, new int[]{0});
            }
            return Memory.wrap(env, value);
        } catch (YAMLException e) {
            env.exception(ProcessorException.class, e.getMessage());
            return Memory.NULL;
        }
    }

    @Override
    @Signature
    public Memory format(Environment env, Memory... args) {
        try {
            return StringMemory.valueOf(yaml.dump(storage ? storageMemory(env, args[0], 0) : Memory.unwrap(env, args[0], true)));
        } catch (YAMLException e) {
            env.exception(ProcessorException.class, e.getMessage());
            return Memory.NULL;
        }
    }

    @Override
    @Signature
    public Memory formatTo(Environment env, Memory... args) {
        try {
            yaml.dump(
                    storage ? storageMemory(env, args[0], 0) : Memory.unwrap(env, args[0], true),
                    new OutputStreamWriter(Stream.getOutputStream(env, args[0]), env.getDefaultCharset())
            );

            return Memory.NULL;
        } catch (YAMLException e) {
            env.exception(ProcessorException.class, e.getMessage());
            return Memory.NULL;
        }
    }

    private Object storageValue(Object value, int depth, int[] nodes) {
        if (depth >= 128 || ++nodes[0] > 100000) throw new YAMLException("YAML document is too complex");
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String || key instanceof Integer || key instanceof Long)) throw new YAMLException("YAML mapping keys must be strings or integers");
                if (result.containsKey(key.toString())) throw new YAMLException("Colliding YAML mapping keys");
                result.put(key.toString(), storageValue(entry.getValue(), depth + 1, nodes));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(storageValue(item, depth + 1, nodes));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Double && Double.isFinite((Double) value)) return value;
        throw new YAMLException("Unsupported YAML value");
    }

    private Object storageMemory(Environment env, Memory value, int depth) {
        if (depth >= 128) throw new YAMLException("YAML value nesting is too deep");
        if (value.isArray()) {
            ArrayMemory array = value.toValue(ArrayMemory.class);
            if (depth > 1 && array.isList()) {
                List<Object> result = new ArrayList<>();
                for (Memory item : array.values()) result.add(storageMemory(env, item, depth + 1));
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            php.runtime.lang.ForeachIterator iterator = array.getNewIterator(env);
            while (iterator.next()) result.put(iterator.getKey().toString(), storageMemory(env, iterator.getValue(), depth + 1));
            return result;
        }
        if (depth < 2 || value.isObject() || value.isResource()) throw new YAMLException("YAML storage requires mapping sections with scalar/array values");
        Object result = Memory.unwrap(env, value);
        if (result instanceof Double && !Double.isFinite((Double) result)) throw new YAMLException("Invalid YAML number");
        return result;
    }
}
