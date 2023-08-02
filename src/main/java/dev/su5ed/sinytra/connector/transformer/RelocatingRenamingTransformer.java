package dev.su5ed.sinytra.connector.transformer;

import net.minecraftforge.fart.api.ClassProvider;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.fart.internal.ClassProviderImpl;
import net.minecraftforge.fart.internal.EnhancedClassRemapper;
import net.minecraftforge.fart.internal.EnhancedRemapper;
import net.minecraftforge.fart.internal.RenamingTransformer;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class RelocatingRenamingTransformer extends RenamingTransformer {
    private static final Map<String, String> RELOCATE = Map.of(
        "org/spongepowered/", "org/spongepowered/reloc/",
        "com/llamalad7/mixinextras/", "com/llamalad7/mixinextras/reloc/"
    );

    public static Transformer create(ClassProvider classProvider, Consumer<String> log, IMappingFile mappingFile, Map<String, String> flatMappings) {
        RenamingClassProvider reverseProvider = new RenamingClassProvider(classProvider, mappingFile, mappingFile.reverse(), log);
        EnhancedRemapper enhancedRemapper = new RelocatingEnhancedRemapper(reverseProvider, mappingFile, flatMappings, log);
        return new RelocatingRenamingTransformer(enhancedRemapper, false);
    }

    public RelocatingRenamingTransformer(EnhancedRemapper remapper, boolean collectAbstractParams) {
        super(remapper, collectAbstractParams);
    }

    @Override
    public ClassEntry process(ClassEntry entry) {
        ClassEntry processed = super.process(entry);
        String name = relocateValue(entry.getName());
        return ClassEntry.create(name, processed.getTime(), processed.getData());
    }

    @Override
    public ResourceEntry process(ResourceEntry entry) {
        String name = entry.getName();
        if (name.startsWith("META-INF/services/") || name.endsWith(".json")) {
            String content = new String(entry.getData(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> relocateEntry : RELOCATE.entrySet()) {
                content = content.replace(
                    relocateEntry.getKey().replace('/', '.'),
                    relocateEntry.getValue().replace('/', '.')
                );
            }
            byte[] remapped = content.getBytes(StandardCharsets.UTF_8);
            return ResourceEntry.create(name, entry.getTime(), remapped);
        }
        return super.process(entry);
    }

    private static String relocateValue(String key) {
        for (Map.Entry<String, String> entry : RELOCATE.entrySet()) {
            String relocKey = entry.getKey();
            if (key.startsWith(relocKey)) {
                return key.replace(relocKey, entry.getValue());
            }
        }
        return key;
    }

    private static final class RenamingClassProvider implements ClassProvider {
        private final ClassProvider upstream;
        private final IMappingFile forwardMapping;
        private final EnhancedRemapper remapper;

        private final Map<String, Optional<IClassInfo>> classCache = new ConcurrentHashMap<>();

        private RenamingClassProvider(ClassProvider upstream, IMappingFile forwardMapping, IMappingFile reverseMapping, Consumer<String> log) {
            this.upstream = upstream;
            this.forwardMapping = forwardMapping;
            this.remapper = new EnhancedRemapper(upstream, reverseMapping, log);
        }

        @Override
        public Optional<? extends IClassInfo> getClass(String s) {
            return this.classCache.computeIfAbsent(s, this::computeClassInfo)
                .or(() -> this.upstream.getClass(s));
        }

        @Override
        public Optional<byte[]> getClassBytes(String cls) {
            return this.upstream.getClassBytes(this.forwardMapping.remapClass(cls));
        }

        private Optional<IClassInfo> computeClassInfo(String cls) {
            return getClassBytes(cls).map(data -> {
                ClassReader reader = new ClassReader(data);
                ClassWriter writer = new ClassWriter(0);
                ClassRemapper remapper = new EnhancedClassRemapper(writer, this.remapper, null);
                MixinTargetAnalyzer analyzer = new MixinTargetAnalyzer(Opcodes.ASM9, remapper);
                reader.accept(analyzer, ClassReader.SKIP_CODE);

                byte[] remapped = writer.toByteArray();
                IClassInfo info = new ClassProviderImpl.ClassInfo(remapped);
                return !analyzer.targets.isEmpty() ? new MixinClassInfo(info, analyzer.targets) : info;
            });
        }

        @Override
        public void close() throws IOException {
            this.upstream.close();
        }
    }

    private static class MixinTargetAnalyzer extends ClassVisitor {
        private final Set<String> targets = new HashSet<>();

        public MixinTargetAnalyzer(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new MixinAnnotationVisitor(this.api, super.visitAnnotation(descriptor, visible), this.targets, null);
        }
    }

    private static class MixinAnnotationVisitor extends AnnotationVisitor {
        private final Set<String> targets;
        private final String attributeName;

        public MixinAnnotationVisitor(int api, AnnotationVisitor annotationVisitor, Set<String> targets, String attributeName) {
            super(api, annotationVisitor);

            this.targets = targets;
            this.attributeName = attributeName;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name, value);
            if ("value".equals(this.attributeName) && value instanceof Type type) {
                this.targets.add(type.getInternalName());
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new MixinAnnotationVisitor(this.api, super.visitArray(name), this.targets, name);
        }
    }

    private static class RelocatingEnhancedRemapper extends EnhancedRemapper {
        private final Map<String, String> flatMappings;

        public RelocatingEnhancedRemapper(ClassProvider classProvider, IMappingFile map, Map<String, String> flatMappings, Consumer<String> log) {
            super(classProvider, map, log);
            this.flatMappings = flatMappings;
        }

        @Override
        public String map(final String key) {
            String fastMapped = this.flatMappings.get(key);
            if (fastMapped != null) {
                return fastMapped;
            }
            String remapped = super.map(key);
            return key.equals(remapped) ? relocateValue(key) : remapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String fastMapped = this.flatMappings.get(name);
            if (fastMapped != null) {
                return fastMapped;
            }
            return this.classProvider.getClass(owner)
                .map(cls -> {
                    if (cls instanceof MixinClassInfo mcls) {
                        for (String parent : mcls.computedParents()) {
                            String mapped = super.mapFieldName(parent, name, descriptor);
                            if (!name.equals(mapped)) {
                                return mapped;
                            }
                        }
                    }
                    return null;
                })
                .orElseGet(() -> super.mapFieldName(owner, name, descriptor));
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String fastMapped = this.flatMappings.get(name);
            if (fastMapped != null) {
                return fastMapped;
            }
            return super.mapMethodName(owner, name, descriptor);
        }

        @Override
        public String mapPackageName(String name) {
            // We don't need to map these
            return name;
        }

        // An attempt at remapping reflection calls and mixin method names
        @Override
        public Object mapValue(Object value) {
            if (value instanceof String str) {
                for (Map.Entry<String, String> entry : RELOCATE.entrySet()) {
                    String pKey = entry.getKey().replace('/', '.');
                    if (str.startsWith(pKey)) {
                        return str.replace(pKey, entry.getValue().replace('/', '.'));
                    }
                }
                String mapped = this.flatMappings.get(str);
                if (mapped != null) {
                    return mapped;
                }
            }
            return super.mapValue(value);
        }
    }

    private record MixinClassInfo(ClassProvider.IClassInfo wrapped, Set<String> computedParents) implements ClassProvider.IClassInfo {
        // Hacky way to "inject" members from the computed parent while preserving the real ones
        @Override
        public Collection<String> getInterfaces() {
            return Stream.concat(this.wrapped.getInterfaces().stream(), this.computedParents.stream()).toList();
        }

        //@formatter:off
        @Override public int getAccess() {return this.wrapped.getAccess();}
        @Override public String getName() {return this.wrapped.getName();}
        @Override public @Nullable String getSuper() {return this.wrapped.getSuper();}
        @Override public Collection<? extends ClassProvider.IFieldInfo> getFields() {return this.wrapped.getFields();}
        @Override public Optional<? extends ClassProvider.IFieldInfo> getField(String name) {return this.wrapped.getField(name);}
        @Override public Collection<? extends ClassProvider.IMethodInfo> getMethods() {return this.wrapped.getMethods();}
        @Override public Optional<? extends ClassProvider.IMethodInfo> getMethod(String name, String desc) {return this.wrapped.getMethod(name, desc);}
        //@formatter:on
    }
}