/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphservice.tree;

import java.util.HashMap;
import java.util.Map;

/**
 * A test stand-in for Facebook's JNI tree, the base of every model. The GenAI feed rule reads its
 * public {@code mTypeTag} by reflection, and the GenAI reel rule reads a reel attribution's flag
 * through the public readers Facebook keeps on it: {@code getBooleanValue(int)}, keyed by the hash
 * of the field's GraphQL name, {@code hasFieldValue(int)} and {@code getTypeName()}. Like
 * Facebook's, a boolean the tree doesn't hold reads false. Every boolean read is counted, so a
 * test can show that a switched-off rule reads nothing.
 */
public class TreeJNI {
    public final int mTypeTag;
    private final Map<Integer, Boolean> booleans = new HashMap<>();
    private final String typeName;
    private boolean valid = true;
    public int booleanReads;

    public TreeJNI(int typeTag) {
        this(typeTag, null);
    }

    /** A tree answering {@code typeName}, as a Pando attribution does. */
    public TreeJNI(String typeName) {
        this(0, typeName);
    }

    private TreeJNI(int typeTag, String typeName) {
        this.mTypeTag = typeTag;
        this.typeName = typeName;
    }

    /** Sets the boolean field named {@code field}, the way a fetched tree holds it. */
    public TreeJNI holding(String field, boolean value) {
        booleans.put(field.hashCode(), value);
        return this;
    }

    /** A tree whose native side is gone, as after Facebook releases it. */
    public TreeJNI releasedTree() {
        valid = false;
        return this;
    }

    public final boolean getBooleanValue(int key) {
        booleanReads++;
        Boolean value = booleans.get(key);
        return value != null && value;
    }

    public final boolean hasFieldValue(int key) {
        return booleans.containsKey(key);
    }

    public boolean isValidGraphServicesJNIModel() {
        return valid;
    }

    public String getTypeName() {
        return typeName;
    }
}
