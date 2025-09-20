package com.example.scheduler.service.runner;

import com.example.scheduler.service.TaskRunner;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component("code.index")
@RequiredArgsConstructor
public class CodeIndexRunner implements TaskRunner {

    @Override
    public String type() {
        return "code.index";
    }

    @Override
    public void run(JsonNode payload) throws Exception {
        Path root = Paths.get(req(payload, "root"));
        Path out = Paths.get(req(payload, "out"));
        Files.createDirectories(out);

        // --- Path filters ---
        List<String> includeGlobs = readStringArray(payload, "includes"); // 可选
        List<String> excludeGlobs = readStringArray(payload, "excludes"); // 可选（在默认排除之外再加）
        PathFilter filter = new PathFilter(root, includeGlobs, excludeGlobs);

        // --- Symbol solver ---
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false)); // JRE
        solver.add(new JavaParserTypeSolver(root.toFile())); // 源码树

        if (payload != null && payload.has("classpath") && payload.get("classpath").isArray()) {
            for (JsonNode n : payload.get("classpath")) {
                String cp = n.asText();
                try {
                    Path p = Paths.get(cp);
                    if (Files.isRegularFile(p) && cp.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        solver.add(new JarTypeSolver(p));
                    } else if (Files.isDirectory(p)) {
                        // 若给的是源码目录/依赖源码，也支持
                        solver.add(new JavaParserTypeSolver(p));
                    } else {
                        log.warn("Classpath entry not usable: {}", cp);
                    }
                } catch (Exception e) {
                    log.warn("Classpath entry not usable by symbol solver: {}", cp, e);
                }
            }
        }
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11).setAttributeComments(false).setDoNotAssignCommentsPrecedingEmptyLines(false).setSymbolResolver(new JavaSymbolSolver(solver));

        // --- Collect java files with filters applied ---
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".java")).filter(filter::accept).forEach(javaFiles::add);
        }

        // --- CSVs ---
        try (CSVPrinter classesCsv = csv(out.resolve("classes.csv"), "package", "kind", "name", "qualified", "modifiers", "extends", "implements", "typeParams", "annotations", "deprecated", "javadoc", "file", "line");

             CSVPrinter methodsCsv = csv(out.resolve("methods.csv"), "class", "method", "signature", "returnType", "modifiers", "annotations", "parameters", "throws", "deprecated", "javadoc", "file", "line");

             CSVPrinter fieldsCsv = csv(out.resolve("fields.csv"), "class", "field", "type", "modifiers", "annotations", "deprecated", "javadoc", "file", "line");

             CSVPrinter callsCsv = csv(out.resolve("calls.csv"), "callerClass", "callerMethod", "calleeQualified", "calleeSignature", "file", "line")) {
            for (Path f : javaFiles) {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                CompilationUnit cu;
                try {
                    cu = StaticJavaParser.parse(code);
                } catch (Throwable ex) {
                    log.warn("Parse failed: {}", f, ex);
                    continue;
                }

                final String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                final String fileRel = root.relativize(f).toString();

                // findAll(TypeDeclaration) 会包含内部类/局部类的声明，满足“内部类”需求
                for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                    String kind = kindOf(td);
                    String simpleName = td.getNameAsString();
                    String qualified = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
                    String mods = td.getModifiers().stream().map(Object::toString).collect(Collectors.joining(" "));
                    String ext = extractExtends(td);
                    String impl = extractImplements(td);
                    String typeParams = (td instanceof ClassOrInterfaceDeclaration) ? ((ClassOrInterfaceDeclaration) td).getTypeParameters().toString() : "";
                    String anns = joinAnnotations(td.getAnnotations());
                    boolean deprecated = hasDeprecated(td.getAnnotations());
                    String jdoc = javadocSummary(td);

                    write(classesCsv, pkg, kind, simpleName, qualified, mods, ext, impl, typeParams, anns, deprecated, jdoc, fileRel, line(td));

                    // --- fields.csv ---
                    for (BodyDeclaration<?> m : td.getMembers()) {
                        if (m instanceof FieldDeclaration) {
                            FieldDeclaration fd = (FieldDeclaration) m;
                            String fmods = fd.getModifiers().stream().map(Object::toString).collect(Collectors.joining(" "));
                            String fans = joinAnnotations(fd.getAnnotations());
                            boolean fdep = hasDeprecated(fd.getAnnotations());
                            String fjdoc = javadocSummary(fd);
                            for (VariableDeclarator v : fd.getVariables()) {
                                write(fieldsCsv, qualified, v.getNameAsString(), v.getTypeAsString(), fmods, fans, fdep, fjdoc, fileRel, line(fd));
                            }
                        }
                    }

                    // --- methods.csv + calls.csv ---
                    for (BodyDeclaration<?> m : td.getMembers()) {
                        if (m instanceof MethodDeclaration) {
                            MethodDeclaration md = (MethodDeclaration) m;
                            String mname = md.getNameAsString();
                            String ret = md.getType().asString();
                            String mmods = md.getModifiers().stream().map(Object::toString).collect(Collectors.joining(" "));
                            String manns = joinAnnotations(md.getAnnotations());
                            boolean mdep = hasDeprecated(md.getAnnotations());
                            String params = md.getParameters().stream().map(p -> p.getTypeAsString() + " " + p.getNameAsString()).collect(Collectors.joining(", "));
                            String sig = mname + "(" + md.getParameters().stream().map(p -> p.getTypeAsString()).collect(Collectors.joining(",")) + ")";
                            String throwses = md.getThrownExceptions().stream().map(Object::toString).collect(Collectors.joining(", "));
                            String mjdoc = javadocSummary(md);

                            write(methodsCsv, qualified, mname, sig, ret, mmods, manns, params, throwses, mdep, mjdoc, fileRel, line(md));

                            for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                                String calleeQualified = "";
                                String calleeSignature = call.getNameAsString() + "(" + call.getArguments().stream().map(a -> "?").collect(Collectors.joining(",")) + ")";
                                try {
                                    ResolvedMethodDeclaration r = call.resolve();
                                    calleeQualified = r.getQualifiedName();
                                    calleeSignature = r.getSignature();
                                } catch (Throwable ignore) {
                                    // 解析失败降级：保留局部名与占位签名
                                }
                                write(callsCsv, qualified, sig, calleeQualified, calleeSignature, fileRel, line(call));
                            }
                        }
                    }
                }
            }
        }

        log.info("Code index with filters & extra dimensions done: {}", out);
    }

    // ---------------- helpers ----------------

    private String req(JsonNode p, String k) {
        if (p == null || !p.hasNonNull(k)) throw new IllegalArgumentException("payload." + k + " required");
        return p.get(k).asText();
    }

    private List<String> readStringArray(JsonNode p, String key) {
        if (p == null || !p.has(key) || !p.get(key).isArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonNode n : p.get(key)) {
            if (n != null && n.isTextual()) list.add(n.asText());
        }
        return list;
    }

    private CSVPrinter csv(Path file, String... headers) throws IOException {
        Files.createDirectories(file.getParent());
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader(headers).build();
        return new CSVPrinter(new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8), fmt);
    }

    private void write(CSVPrinter p, Object... vals) {
        try {
            p.printRecord(vals);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String kindOf(TypeDeclaration<?> td) {
        if (td.isClassOrInterfaceDeclaration()) {
            return td.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        } else if (td.isEnumDeclaration()) {
            return "enum";
        } else if (td.isAnnotationDeclaration()) {
            return "annotation";
        }
        return td.getClass().getSimpleName();
    }

    private String extractExtends(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
            return c.getExtendedTypes().stream().map(Object::toString).collect(Collectors.joining(", "));
        } else if (td instanceof EnumDeclaration) {
            return "";
        }
        return "";
    }

    private String extractImplements(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
            NodeList<ClassOrInterfaceType> impls = c.getImplementedTypes();
            return impls.stream().map(Object::toString).collect(Collectors.joining(", "));
        }
        return "";
    }

    private String joinAnnotations(NodeList<AnnotationExpr> anns) {
        if (anns == null || anns.isEmpty()) return "";
        return anns.stream().map(Object::toString).collect(Collectors.joining(" | "));
    }

    private boolean hasDeprecated(NodeList<AnnotationExpr> anns) {
        if (anns == null) return false;
        for (AnnotationExpr a : anns) {
            String n = a.getNameAsString();
            if ("Deprecated".equals(n) || "java.lang.Deprecated".equals(n)) return true;
        }
        return false;
    }

    private String javadocSummary(BodyDeclaration<?> node) {
        return node.getComment().filter(c -> c instanceof JavadocComment).map(c -> (JavadocComment) c).map(jc -> {
            try {
                Javadoc jd = jc.parse();
                return jd.getDescription().toText().replaceAll("\\s+", " ").trim();
            } catch (Exception e) {
                return jc.getContent().replaceAll("\\s+", " ").trim();
            }
        }).orElse("");
    }

    private String javadocSummary(TypeDeclaration<?> node) {
        return node.getComment().filter(c -> c instanceof JavadocComment).map(c -> (JavadocComment) c).map(jc -> {
            try {
                Javadoc jd = jc.parse();
                return jd.getDescription().toText().replaceAll("\\s+", " ").trim();
            } catch (Exception e) {
                return jc.getContent().replaceAll("\\s+", " ").trim();
            }
        }).orElse("");
    }

    private int line(com.github.javaparser.ast.Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(-1);
    }

    // ---------------- filtering ----------------

    /**
     * 过滤规则：
     * 1) 默认排除：target/, build/, .idea/, generated/, test**/

    /**
     * （任何路径片段为 test 的目录）
     * 2) includes：glob 白名单（相对 root）；若非空，则必须命中其一才保留
     * 3) excludes：glob 黑名单（相对 root）；命中则排除
     */
    static class PathFilter {
        private final Path root;
        private final List<PathMatcher> includeMatchers;
        private final List<PathMatcher> excludeMatchers;

        private static final List<String> DEFAULT_EXCLUDES = Arrays.asList("target/**", "build/**", ".idea/**", "generated/**", "**/test/**", "**/tests/**", "**/it/**");

        PathFilter(Path root, List<String> includes, List<String> excludes) {
            this.root = root.toAbsolutePath().normalize();
            FileSystem fs = FileSystems.getDefault();
            List<String> ex = new ArrayList<>(DEFAULT_EXCLUDES);
            if (excludes != null) ex.addAll(excludes);

            this.includeMatchers = toMatchers(fs, includes);
            this.excludeMatchers = toMatchers(fs, ex);
        }

        boolean accept(Path p) {
            Path rel = safeRel(root, p);
            String unix = toUnix(rel);

            // 默认排除
            for (PathMatcher m : excludeMatchers) {
                if (m.matches(Paths.get(unix))) return false;
            }
            // 若定义了 includes，必须至少命中一个
            if (!includeMatchers.isEmpty()) {
                for (PathMatcher m : includeMatchers) {
                    if (m.matches(Paths.get(unix))) return true;
                }
                return false;
            }
            return true;
        }

        private static List<PathMatcher> toMatchers(FileSystem fs, List<String> globs) {
            List<PathMatcher> ms = new ArrayList<>();
            if (globs != null) {
                for (String g : globs) {
                    if (g == null || g.isEmpty()) continue;
                    String pat = g.startsWith("glob:") ? g : "glob:" + g;
                    ms.add(fs.getPathMatcher(pat));
                }
            }
            return ms;
        }

        private static Path safeRel(Path root, Path p) {
            try {
                return root.relativize(p.toAbsolutePath().normalize());
            } catch (Exception ignore) {
                return p.getFileName();
            }
        }

        private static String toUnix(Path rel) {
            String s = rel.toString();
            return s.replace('\\', '/');
        }
    }
}