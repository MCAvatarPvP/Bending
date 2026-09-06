package com.projectkorra.build;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Rejects comparisons that depend on reusing a platform wrapper instance. */
public final class WrapperReferenceEqualityPlugin implements Plugin {
    public static final String NAME = "WrapperReferenceEquality";
    private static final List<String> WRAPPER_TYPES = List.of(
            "com.projectkorra.projectkorra.platform.mc.entity.Entity",
            "com.projectkorra.projectkorra.platform.mc.block.Block",
            "com.projectkorra.projectkorra.platform.mc.OfflinePlayer");

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        Trees trees = Trees.instance(task);
        Set<BinaryTree> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ANALYZE) return;
                TreePath path = trees.getPath(event.getTypeElement());
                if (path == null) return;
                List<TypeMirror> wrappers = WRAPPER_TYPES.stream()
                        .map(task.getElements()::getTypeElement)
                        .filter(java.util.Objects::nonNull)
                        .map(Element::asType)
                        .toList();
                if (!wrappers.isEmpty()) {
                    new Scanner(trees, task.getTypes(), task.getElements(), wrappers, reported).scan(path, null);
                }
            }
        });
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final Elements elements;
        private final List<TypeMirror> wrappers;
        private final Set<BinaryTree> reported;

        private Scanner(Trees trees, Types types, Elements elements,
                        List<TypeMirror> wrappers, Set<BinaryTree> reported) {
            this.trees = trees;
            this.types = types;
            this.elements = elements;
            this.wrappers = wrappers;
            this.reported = reported;
        }

        @Override
        public Void visitBinary(BinaryTree node, Void unused) {
            if (node.getKind() == Tree.Kind.EQUAL_TO || node.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                TreePath left = new TreePath(getCurrentPath(), node.getLeftOperand());
                TreePath right = new TreePath(getCurrentPath(), node.getRightOperand());
                if (isReference(left) && isReference(right)
                        && (isWrapper(left) || isWrapper(right))
                        && !isSuppressed() && reported.add(node)) {
                    CompilationUnitTree unit = getCurrentPath().getCompilationUnit();
                    trees.printMessage(Diagnostic.Kind.ERROR,
                            "[" + NAME + "] Platform wrappers must not be compared with == or !=. "
                                    + "Use .equals() or Objects.equals() for value equality, or compare native "
                                    + "handle() references when checking a login session. For an intentional "
                                    + "identity fast path, use @SuppressWarnings(\"" + NAME + "\") "
                                    + "on the enclosing method and document why it is safe.",
                            node, unit);
                }
            }
            return super.visitBinary(node, unused);
        }

        private boolean isReference(TreePath path) {
            TypeMirror type = trees.getTypeMirror(path);
            if (type == null || type.getKind().isPrimitive()
                    || type.getKind() == TypeKind.NULL || type.getKind() == TypeKind.ERROR) return false;
            for (TreePath inner = path; inner != null; inner = innerExpression(inner)) {
                if (inner.getLeaf().getKind() == Tree.Kind.NULL_LITERAL) return false;
            }
            return true;
        }

        private boolean isWrapper(TreePath path) {
            TypeMirror type = trees.getTypeMirror(path);
            if (type != null && type.getKind() != TypeKind.NULL && type.getKind() != TypeKind.ERROR
                    && wrappers.stream().anyMatch(wrapper -> types.isSubtype(type, wrapper))) {
                return true;
            }
            // Casting a wrapper to Object must not conceal a reference comparison.
            TreePath inner = innerExpression(path);
            return inner != null && isWrapper(inner);
        }

        private static TreePath innerExpression(TreePath path) {
            ExpressionTree inner = switch (path.getLeaf()) {
                case ParenthesizedTree parentheses -> parentheses.getExpression();
                case TypeCastTree cast -> cast.getExpression();
                default -> null;
            };
            return inner == null ? null : new TreePath(path, inner);
        }

        private boolean isSuppressed() {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                switch (path.getLeaf().getKind()) {
                    case METHOD, VARIABLE -> {
                        Element element = trees.getElement(path);
                        if (element == null) continue;
                        for (var annotation : element.getAnnotationMirrors()) {
                            if (!annotation.getAnnotationType().toString().equals("java.lang.SuppressWarnings")) continue;
                            if (elements.getElementValuesWithDefaults(annotation).values().stream()
                                    .anyMatch(Scanner::containsRuleName)) return true;
                        }
                    }
                    default -> { }
                }
            }
            return false;
        }

        private static boolean containsRuleName(AnnotationValue annotation) {
            Object value = annotation.getValue();
            return NAME.equals(value) || value instanceof List<?> values
                    && values.stream().anyMatch(item -> item instanceof AnnotationValue nested && containsRuleName(nested));
        }
    }
}
