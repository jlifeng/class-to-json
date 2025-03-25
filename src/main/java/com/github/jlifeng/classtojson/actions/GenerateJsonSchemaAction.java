package com.github.jlifeng.classtojson.actions;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;

public class GenerateJsonSchemaAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (project == null || files == null) return;

        List<PsiClass> classes = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        // 收集所有选中的 Java 类
        for (VirtualFile file : files) {
            if (file.getFileType() == JavaFileType.INSTANCE) {
                PsiJavaFile javaFile = (PsiJavaFile) PsiManager.getInstance(project).findFile(file);
                if (javaFile != null) {
                    Arrays.stream(javaFile.getClasses())
                            .filter(clazz -> !clazz.isInterface() && !clazz.isEnum())
                            .forEach(classes::add);
                }
            }
        }


        for (PsiClass clazz : classes) {
            JSONObject schema = convertClassToJsonSchema(clazz, processed, project);
            if (schema.isEmpty()) {
                Messages.showMessageDialog(project, "No schema defined for class: " + clazz.getName(), "Error", null);
                continue;
            }

            // 保存 JSON Schema 到文件
            String fileName = clazz.getName() + "_" + System.currentTimeMillis() + ".json";
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    VirtualFile baseDir = project.getProjectFile();
                    if (baseDir != null) {
                        baseDir = baseDir.getParent().getParent();
                        VirtualFile schemaFile = baseDir.createChildData(this, fileName);
                        VfsUtil.saveText(schemaFile, JSON.toJSONString(schema));
                        // 使用 Notification API 输出日志到 Event Log
                        Notification notification = new Notification(
                                "GenerateJsonSchema", // 组名，任意字符串标识
                                "Success",            // 标题
                                "Schema saved to: " + schemaFile.getPath(), // 内容
                                NotificationType.INFORMATION // 类型
                        );
                        Notifications.Bus.notify(notification, project); // 在指定项目中显示
                    }
                } catch (IOException ex) {
                    Notification errorNotification = new Notification(
                            "GenerateJsonSchema",
                            "Error",
                            "Failed to save schema: " + ex.getMessage(),
                            NotificationType.ERROR
                    );
                    Notifications.Bus.notify(errorNotification, project);
                }
            });
        }
    }

    private JSONObject convertClassToJsonSchema(PsiClass psiClass, Set<String> processed, Project project) {
        String className = Optional.ofNullable(psiClass.getQualifiedName())
                .orElse(psiClass.getName());
        if (processed.contains(className)) {
            return new JSONObject().fluentPut("$ref", "#/definitions/" + className);
        }
        processed.add(className);

        JSONObject schema = new JSONObject();
        schema.fluentPut("type", "object")
                .fluentPut("title", psiClass.getName());

        if (psiClass.isEnum()) {
            schema.fluentPut("type", "string");
            JSONArray enumValues = new JSONArray();
            for (PsiField field : psiClass.getFields()) {
                if (field instanceof PsiEnumConstant) {
                    enumValues.add(field.getName());
                }
            }
            schema.fluentPut("enum", enumValues);
            return schema;
        }

        JSONObject properties = new JSONObject();
        for (PsiField field : psiClass.getFields()) {
            PsiType fieldType = field.getType();
            JSONObject propSchema = new JSONObject();

            // 处理字段类型
            if (fieldType instanceof PsiArrayType) {
                PsiType componentType = ((PsiArrayType) fieldType).getComponentType();
                propSchema.fluentPut("type", "array")
                        .fluentPut("items", processType(componentType, processed, project));

            } else if (fieldType instanceof PsiClassType) {
                PsiClassType classType = (PsiClassType) fieldType;
                PsiClass refClass = classType.resolve();
                if (refClass != null && !isJavaLangType(refClass)) {
                    propSchema = convertClassToJsonSchema(refClass, processed, project);
                } else {
                    propSchema.fluentPut("type", getJsonType(fieldType));
                }

            } else {
                propSchema.fluentPut("type", getJsonType(fieldType));
            }

            // 处理 List 类型
            if (fieldType instanceof PsiClassType) {
                PsiClassType classType = (PsiClassType) fieldType;
                PsiClass psiClassType = classType.resolve();
                if (psiClassType != null && "java.util.List".equals(psiClassType.getQualifiedName())) {
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length > 0) {
                        PsiType parameterType = parameters[0];
                        if (parameterType instanceof PsiClassType) {
                            PsiClassType paramClassType = (PsiClassType) parameterType;
                            PsiClass paramClass = paramClassType.resolve();
                            if (paramClass != null && !isJavaLangType(paramClass)) {
                                propSchema.fluentPut("type", "array")
                                        .fluentPut("items", convertClassToJsonSchema(paramClass, processed, project));
                            } else {
                                propSchema.fluentPut("type", "array")
                                        .fluentPut("items", processType(parameterType, processed, project));
                            }
                        }
                    }
                }
            }

            // 处理 Map 类型
            if (fieldType instanceof PsiClassType) {
                PsiClassType classType = (PsiClassType) fieldType;
                PsiClass psiClassType = classType.resolve();
                if (psiClassType != null && "java.util.Map".equals(psiClassType.getQualifiedName())) {
                    PsiType[] parameters = classType.getParameters();
                    if (parameters.length == 2) {
                        PsiType keyType = parameters[0];
                        PsiType valueType = parameters[1];
                        if (keyType instanceof PsiClassType && valueType instanceof PsiClassType) {
                            PsiClassType keyClassType = (PsiClassType) keyType;
                            PsiClassType valueClassType = (PsiClassType) valueType;
                            PsiClass keyClass = keyClassType.resolve();
                            PsiClass valueClass = valueClassType.resolve();
                            if (keyClass != null && valueClass != null && !isJavaLangType(keyClass) && !isJavaLangType(valueClass)) {
                                propSchema.fluentPut("type", "object");
                                JSONObject additionalProperties = new JSONObject();
                                additionalProperties.fluentPut("$ref", "#/definitions/" + valueClass.getQualifiedName());
                                propSchema.fluentPut("additionalProperties", additionalProperties);
                            } else {
                                propSchema.fluentPut("type", "object");
                                JSONObject additionalProperties = new JSONObject();
                                additionalProperties.fluentPut("type", getJsonType(valueType));
                                propSchema.fluentPut("additionalProperties", additionalProperties);
                            }
                        }
                    }
                }
            }

            // 处理注释
            PsiDocComment docComment = field.getDocComment();
            if (docComment != null) {
                String comment = docComment.getText();
                // 去除注释的 /* 和 */
                comment = comment.replaceAll("/\\*+", "").replaceAll("\\*+/", "").trim();
                // 去除每行开头的 *
                comment = comment.replaceAll("\\s*\\*", "").trim();
                propSchema.fluentPut("description", comment);
            }

            properties.fluentPut(field.getName(), propSchema);
        }

        schema.fluentPut("properties", properties);
        return schema;
    }


    private JSONObject processType(PsiType type, Set<String> processed, Project project) {
        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClass refClass = classType.resolve();
            if (refClass != null && !isJavaLangType(refClass)) {
                return convertClassToJsonSchema(refClass, processed, project);
            }
        }
        return new JSONObject().fluentPut("type", getJsonType(type));
    }

    private String getJsonType(PsiType psiType) {
        if (psiType.equals(PsiTypes.intType()) || psiType.equalsToText("java.lang.String")) {
            return "string";
        }
        if (psiType.equals(PsiTypes.intType()) || psiType.equalsToText("java.lang.Integer")) {
            return "integer";
        }
        if (psiType.equals(PsiTypes.longType()) || psiType.equalsToText("java.lang.Long")) {
            return "integer";
        }
        if (psiType.equals(PsiTypes.booleanType()) || psiType.equalsToText("java.lang.Boolean")) {
            return "boolean";
        }
        if (psiType.equals(PsiTypes.doubleType()) || psiType.equalsToText("java.lang.Double")) {
            return "number";
        }
        if (psiType instanceof PsiArrayType) {
            return "array";
        }
        if (psiType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) psiType;
            PsiClass psiClass = classType.resolve();
            if (psiClass != null) {
                String qualifiedName = psiClass.getQualifiedName();
                if ("java.util.List".equals(qualifiedName) || "java.util.Set".equals(qualifiedName)) {
                    return "array";
                } else if ("java.util.Map".equals(qualifiedName)) {
                    return "object";
                } else if ("java.util.Date".equals(qualifiedName) || "java.util.Calendar".equals(qualifiedName)) {
                    return "string"; // 可以进一步细化为 "format": "date-time"
                } else if (qualifiedName != null && qualifiedName.startsWith("java.time.")) {
                    return "string"; // 可以进一步细化为 "format": "date-time" 或 "format": "date"
                } else if ("java.math.BigDecimal".equals(qualifiedName) || "java.math.BigInteger".equals(qualifiedName)) {
                    return "number";
                } else {
                    return "object";
                }
            }
        }
        return "object";
    }


    private boolean isJavaLangType(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        return qualifiedName != null && qualifiedName.startsWith("java.");
    }

    @Override
    public void update(AnActionEvent e) {
        boolean visible = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) != null &&
                Arrays.stream(e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY))
                        .allMatch(file -> file.getName().endsWith(".java"));
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}