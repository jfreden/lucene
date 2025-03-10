/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.index;

import java.util.Map;
import java.util.Objects;

/**
 * Access to the Field Info file that describes document fields and whether or not they are indexed.
 * Each segment has a separate Field Info file. Objects of this class are thread-safe for multiple
 * readers, but only one thread can be adding documents at a time, with no other reader or writer
 * threads accessing this object.
 */
public final class FieldInfo {
  /** Field's name */
  public final String name;

  /** Internal field number */
  public final int number;

  private DocValuesType docValuesType = DocValuesType.NONE;

  // True if any document indexed term vectors
  private boolean storeTermVector;

  private boolean omitNorms; // omit norms associated with indexed fields

  private final IndexOptions indexOptions;
  private boolean storePayloads; // whether this field stores payloads together with term positions

  private final Map<String, String> attributes;

  private long dvGen;

  /**
   * If both of these are positive it means this field indexed points (see {@link
   * org.apache.lucene.codecs.PointsFormat}).
   */
  private int pointDimensionCount;

  private int pointIndexDimensionCount;
  private int pointNumBytes;

  // if it is a positive value, it means this field indexes vectors
  private final int vectorDimension;
  private final VectorEncoding vectorEncoding;
  private final VectorSimilarityFunction vectorSimilarityFunction;

  // whether this field is used as the soft-deletes field
  private final boolean softDeletesField;

  private final boolean isParentField;

  /**
   * Sole constructor.
   *
   * @lucene.experimental
   */
  public FieldInfo(
      String name,
      int number,
      boolean storeTermVector,
      boolean omitNorms,
      boolean storePayloads,
      IndexOptions indexOptions,
      DocValuesType docValues,
      long dvGen,
      Map<String, String> attributes,
      int pointDimensionCount,
      int pointIndexDimensionCount,
      int pointNumBytes,
      int vectorDimension,
      VectorEncoding vectorEncoding,
      VectorSimilarityFunction vectorSimilarityFunction,
      boolean softDeletesField,
      boolean isParentField) {
    this.name = Objects.requireNonNull(name);
    this.number = number;
    this.docValuesType =
        Objects.requireNonNull(
            docValues, "DocValuesType must not be null (field: \"" + name + "\")");
    this.indexOptions =
        Objects.requireNonNull(
            indexOptions, "IndexOptions must not be null (field: \"" + name + "\")");
    if (indexOptions != IndexOptions.NONE) {
      this.storeTermVector = storeTermVector;
      this.storePayloads = storePayloads;
      this.omitNorms = omitNorms;
    } else { // for non-indexed fields, leave defaults
      this.storeTermVector = false;
      this.storePayloads = false;
      this.omitNorms = false;
    }
    this.dvGen = dvGen;
    this.attributes = Objects.requireNonNull(attributes);
    this.pointDimensionCount = pointDimensionCount;
    this.pointIndexDimensionCount = pointIndexDimensionCount;
    this.pointNumBytes = pointNumBytes;
    this.vectorDimension = vectorDimension;
    this.vectorEncoding = vectorEncoding;
    this.vectorSimilarityFunction = vectorSimilarityFunction;
    this.softDeletesField = softDeletesField;
    this.isParentField = isParentField;
    this.checkConsistency();
  }

  /**
   * Check correctness of the FieldInfo options
   *
   * @throws IllegalArgumentException if some options are incorrect
   */
  public void checkConsistency() {
    if (indexOptions == null) {
      throw new IllegalArgumentException("IndexOptions must not be null (field: '" + name + "')");
    }
    if (indexOptions != IndexOptions.NONE) {
      // Cannot store payloads unless positions are indexed:
      if (indexOptions.compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) < 0 && storePayloads) {
        throw new IllegalArgumentException(
            "indexed field '" + name + "' cannot have payloads without positions");
      }
    } else {
      if (storeTermVector) {
        throw new IllegalArgumentException(
            "non-indexed field '" + name + "' cannot store term vectors");
      }
      if (storePayloads) {
        throw new IllegalArgumentException(
            "non-indexed field '" + name + "' cannot store payloads");
      }
      if (omitNorms) {
        throw new IllegalArgumentException("non-indexed field '" + name + "' cannot omit norms");
      }
    }

    if (docValuesType == null) {
      throw new IllegalArgumentException("DocValuesType must not be null (field: '" + name + "')");
    }
    if (dvGen != -1 && docValuesType == DocValuesType.NONE) {
      throw new IllegalArgumentException(
          "field '"
              + name
              + "' cannot have a docvalues update generation without having docvalues");
    }

    if (pointDimensionCount < 0) {
      throw new IllegalArgumentException(
          "pointDimensionCount must be >= 0; got "
              + pointDimensionCount
              + " (field: '"
              + name
              + "')");
    }
    if (pointIndexDimensionCount < 0) {
      throw new IllegalArgumentException(
          "pointIndexDimensionCount must be >= 0; got "
              + pointIndexDimensionCount
              + " (field: '"
              + name
              + "')");
    }
    if (pointNumBytes < 0) {
      throw new IllegalArgumentException(
          "pointNumBytes must be >= 0; got " + pointNumBytes + " (field: '" + name + "')");
    }

    if (pointDimensionCount != 0 && pointNumBytes == 0) {
      throw new IllegalArgumentException(
          "pointNumBytes must be > 0 when pointDimensionCount="
              + pointDimensionCount
              + " (field: '"
              + name
              + "')");
    }
    if (pointIndexDimensionCount != 0 && pointDimensionCount == 0) {
      throw new IllegalArgumentException(
          "pointIndexDimensionCount must be 0 when pointDimensionCount=0"
              + " (field: '"
              + name
              + "')");
    }
    if (pointNumBytes != 0 && pointDimensionCount == 0) {
      throw new IllegalArgumentException(
          "pointDimensionCount must be > 0 when pointNumBytes="
              + pointNumBytes
              + " (field: '"
              + name
              + "')");
    }

    if (vectorSimilarityFunction == null) {
      throw new IllegalArgumentException(
          "Vector similarity function must not be null (field: '" + name + "')");
    }
    if (vectorDimension < 0) {
      throw new IllegalArgumentException(
          "vectorDimension must be >=0; got " + vectorDimension + " (field: '" + name + "')");
    }

    if (softDeletesField && isParentField) {
      throw new IllegalArgumentException(
          "field can't be used as soft-deletes field and parent document field (field: '"
              + name
              + "')");
    }
  }

  /**
   * Verify that the provided FieldInfo has the same schema as this FieldInfo
   *
   * @param o – other FieldInfo whose schema is verified against this FieldInfo's schema
   * @throws IllegalArgumentException if the field schemas are not the same
   */
  void verifySameSchema(FieldInfo o) {
    String fieldName = this.name;
    verifySameIndexOptions(fieldName, this.indexOptions, o.getIndexOptions());
    if (this.indexOptions != IndexOptions.NONE) {
      verifySameOmitNorms(fieldName, this.omitNorms, o.omitNorms);
      verifySameStoreTermVectors(fieldName, this.storeTermVector, o.storeTermVector);
    }
    verifySameDocValuesType(fieldName, this.docValuesType, o.docValuesType);
    verifySamePointsOptions(
        fieldName,
        this.pointDimensionCount,
        this.pointIndexDimensionCount,
        this.pointNumBytes,
        o.pointDimensionCount,
        o.pointIndexDimensionCount,
        o.pointNumBytes);
    verifySameVectorOptions(
        fieldName,
        this.vectorDimension,
        this.vectorEncoding,
        this.vectorSimilarityFunction,
        o.vectorDimension,
        o.vectorEncoding,
        o.vectorSimilarityFunction);
  }

  /**
   * Verify that the provided index options are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySameIndexOptions(
      String fieldName, IndexOptions indexOptions1, IndexOptions indexOptions2) {
    if (indexOptions1 != indexOptions2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from index options="
              + indexOptions1
              + " to inconsistent index options="
              + indexOptions2);
    }
  }

  /**
   * Verify that the provided docValues type are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySameDocValuesType(
      String fieldName, DocValuesType docValuesType1, DocValuesType docValuesType2) {
    if (docValuesType1 != docValuesType2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from doc values type="
              + docValuesType1
              + " to inconsistent doc values type="
              + docValuesType2);
    }
  }

  /**
   * Verify that the provided store term vectors options are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySameStoreTermVectors(
      String fieldName, boolean storeTermVector1, boolean storeTermVector2) {
    if (storeTermVector1 != storeTermVector2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from storeTermVector="
              + storeTermVector1
              + " to inconsistent storeTermVector="
              + storeTermVector2);
    }
  }

  /**
   * Verify that the provided omitNorms are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySameOmitNorms(String fieldName, boolean omitNorms1, boolean omitNorms2) {
    if (omitNorms1 != omitNorms2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from omitNorms="
              + omitNorms1
              + " to inconsistent omitNorms="
              + omitNorms2);
    }
  }

  /**
   * Verify that the provided points indexing options are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySamePointsOptions(
      String fieldName,
      int pointDimensionCount1,
      int indexDimensionCount1,
      int numBytes1,
      int pointDimensionCount2,
      int indexDimensionCount2,
      int numBytes2) {
    if (pointDimensionCount1 != pointDimensionCount2
        || indexDimensionCount1 != indexDimensionCount2
        || numBytes1 != numBytes2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from points dimensionCount="
              + pointDimensionCount1
              + ", indexDimensionCount="
              + indexDimensionCount1
              + ", numBytes="
              + numBytes1
              + " to inconsistent dimensionCount="
              + pointDimensionCount2
              + ", indexDimensionCount="
              + indexDimensionCount2
              + ", numBytes="
              + numBytes2);
    }
  }

  /**
   * Verify that the provided vector indexing options are the same
   *
   * @throws IllegalArgumentException if they are not the same
   */
  static void verifySameVectorOptions(
      String fieldName,
      int vd1,
      VectorEncoding ve1,
      VectorSimilarityFunction vsf1,
      int vd2,
      VectorEncoding ve2,
      VectorSimilarityFunction vsf2) {
    if (vd1 != vd2 || vsf1 != vsf2 || ve1 != ve2) {
      throw new IllegalArgumentException(
          "cannot change field \""
              + fieldName
              + "\" from vector dimension="
              + vd1
              + ", vector encoding="
              + ve1
              + ", vector similarity function="
              + vsf1
              + " to inconsistent vector dimension="
              + vd2
              + ", vector encoding="
              + ve2
              + ", vector similarity function="
              + vsf2);
    }
  }

  /**
   * Record that this field is indexed with points, with the specified number of dimensions and
   * bytes per dimension.
   */
  public void setPointDimensions(int dimensionCount, int indexDimensionCount, int numBytes) {
    if (dimensionCount <= 0) {
      throw new IllegalArgumentException(
          "point dimension count must be >= 0; got "
              + dimensionCount
              + " for field=\""
              + name
              + "\"");
    }
    if (indexDimensionCount > PointValues.MAX_INDEX_DIMENSIONS) {
      throw new IllegalArgumentException(
          "point index dimension count must be < PointValues.MAX_INDEX_DIMENSIONS (= "
              + PointValues.MAX_INDEX_DIMENSIONS
              + "); got "
              + indexDimensionCount
              + " for field=\""
              + name
              + "\"");
    }
    if (indexDimensionCount > dimensionCount) {
      throw new IllegalArgumentException(
          "point index dimension count must be <= point dimension count (= "
              + dimensionCount
              + "); got "
              + indexDimensionCount
              + " for field=\""
              + name
              + "\"");
    }
    if (numBytes <= 0) {
      throw new IllegalArgumentException(
          "point numBytes must be >= 0; got " + numBytes + " for field=\"" + name + "\"");
    }
    if (numBytes > PointValues.MAX_NUM_BYTES) {
      throw new IllegalArgumentException(
          "point numBytes must be <= PointValues.MAX_NUM_BYTES (= "
              + PointValues.MAX_NUM_BYTES
              + "); got "
              + numBytes
              + " for field=\""
              + name
              + "\"");
    }
    if (pointDimensionCount != 0 && pointDimensionCount != dimensionCount) {
      throw new IllegalArgumentException(
          "cannot change point dimension count from "
              + pointDimensionCount
              + " to "
              + dimensionCount
              + " for field=\""
              + name
              + "\"");
    }
    if (pointIndexDimensionCount != 0 && pointIndexDimensionCount != indexDimensionCount) {
      throw new IllegalArgumentException(
          "cannot change point index dimension count from "
              + pointIndexDimensionCount
              + " to "
              + indexDimensionCount
              + " for field=\""
              + name
              + "\"");
    }
    if (pointNumBytes != 0 && pointNumBytes != numBytes) {
      throw new IllegalArgumentException(
          "cannot change point numBytes from "
              + pointNumBytes
              + " to "
              + numBytes
              + " for field=\""
              + name
              + "\"");
    }

    pointDimensionCount = dimensionCount;
    pointIndexDimensionCount = indexDimensionCount;
    pointNumBytes = numBytes;

    this.checkConsistency();
  }

  /** Return point data dimension count */
  public int getPointDimensionCount() {
    return pointDimensionCount;
  }

  /** Return point data dimension count */
  public int getPointIndexDimensionCount() {
    return pointIndexDimensionCount;
  }

  /** Return number of bytes per dimension */
  public int getPointNumBytes() {
    return pointNumBytes;
  }

  /** Returns the number of dimensions of the vector value */
  public int getVectorDimension() {
    return vectorDimension;
  }

  /** Returns the number of dimensions of the vector value */
  public VectorEncoding getVectorEncoding() {
    return vectorEncoding;
  }

  /** Returns {@link VectorSimilarityFunction} for the field */
  public VectorSimilarityFunction getVectorSimilarityFunction() {
    return vectorSimilarityFunction;
  }

  /** Record that this field is indexed with docvalues, with the specified type */
  public void setDocValuesType(DocValuesType type) {
    if (type == null) {
      throw new NullPointerException("DocValuesType must not be null (field: \"" + name + "\")");
    }
    if (docValuesType != DocValuesType.NONE
        && type != DocValuesType.NONE
        && docValuesType != type) {
      throw new IllegalArgumentException(
          "cannot change DocValues type from "
              + docValuesType
              + " to "
              + type
              + " for field \""
              + name
              + "\"");
    }
    docValuesType = type;
    this.checkConsistency();
  }

  /** Returns IndexOptions for the field, or IndexOptions.NONE if the field is not indexed */
  public IndexOptions getIndexOptions() {
    return indexOptions;
  }

  /**
   * Returns name of this field
   *
   * @return name
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the field number
   *
   * @return field number
   */
  public int getFieldNumber() {
    return number;
  }

  /**
   * Returns {@link DocValuesType} of the docValues; this is {@code DocValuesType.NONE} if the field
   * has no docvalues.
   */
  public DocValuesType getDocValuesType() {
    return docValuesType;
  }

  /** Sets the docValues generation of this field. */
  void setDocValuesGen(long dvGen) {
    this.dvGen = dvGen;
    this.checkConsistency();
  }

  /** Returns the docValues generation of this field, or -1 if no docValues updates exist for it. */
  public long getDocValuesGen() {
    return dvGen;
  }

  void setStoreTermVectors() {
    storeTermVector = true;
    this.checkConsistency();
  }

  void setStorePayloads() {
    if (indexOptions.compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0) {
      storePayloads = true;
    }
    this.checkConsistency();
  }

  /** Returns true if norms are explicitly omitted for this field */
  public boolean omitsNorms() {
    return omitNorms;
  }

  /** Omit norms for this field. */
  public void setOmitsNorms() {
    if (indexOptions == IndexOptions.NONE) {
      throw new IllegalStateException("cannot omit norms: this field is not indexed");
    }
    omitNorms = true;
    this.checkConsistency();
  }

  /** Returns true if this field actually has any norms. */
  public boolean hasNorms() {
    return indexOptions != IndexOptions.NONE && omitNorms == false;
  }

  /** Returns true if any payloads exist for this field. */
  public boolean hasPayloads() {
    return storePayloads;
  }

  /** Returns true if any term vectors exist for this field. */
  public boolean hasVectors() {
    return storeTermVector;
  }

  /** Returns whether any (numeric) vector values exist for this field */
  public boolean hasVectorValues() {
    return vectorDimension > 0;
  }

  /** Get a codec attribute value, or null if it does not exist */
  public String getAttribute(String key) {
    return attributes.get(key);
  }

  /**
   * Puts a codec attribute value.
   *
   * <p>This is a key-value mapping for the field that the codec can use to store additional
   * metadata, and will be available to the codec when reading the segment via {@link
   * #getAttribute(String)}
   *
   * <p>If a value already exists for the key in the field, it will be replaced with the new value.
   * If the value of the attributes for a same field is changed between the documents, the behaviour
   * after merge is undefined.
   */
  public String putAttribute(String key, String value) {
    return attributes.put(key, value);
  }

  /** Returns internal codec attributes map. */
  public Map<String, String> attributes() {
    return attributes;
  }

  /**
   * Returns true if this field is configured and used as the soft-deletes field. See {@link
   * IndexWriterConfig#softDeletesField}
   */
  public boolean isSoftDeletesField() {
    return softDeletesField;
  }

  /**
   * Returns true if this field is configured and used as the parent document field field. See
   * {@link IndexWriterConfig#setParentField(String)}
   */
  public boolean isParentField() {
    return isParentField;
  }
}
