package org.apache.lucene.search;

import org.apache.lucene.document.Document;
import org.apache.lucene.util.BytesRef;

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

/**
 * A Filter that restricts search results to a range of term
 * values in a given field.
 *
 * <p>This filter matches the documents looking for terms that fall into the
 * supplied range according to {@link
 * Byte#compareTo(Byte)}.
 *
 * <p>If you construct a large number of range filters with different ranges but on the 
 * same field, {@link DocValuesRangeFilter} may have significantly better performance. 
 * @since 2.9
 */
public class LeftZeroPadTermRangeFilter extends MultiTermQueryWrapperFilter<LeftZeroPadTermRangeQuery> {
  private final String desc;

  /**
   * @param fieldName The field this range applies to
   * @param lowerTerm The lower bound on this range
   * @param upperTerm The upper bound on this range
   * @param includeLower Does this range include the lower bound?
   * @param includeUpper Does this range include the upper bound?
   * @throws IllegalArgumentException if both terms are null or if
   *  lowerTerm is null and includeLower is true (similar for upperTerm
   *  and includeUpper)
   */
  public LeftZeroPadTermRangeFilter(String fieldName, BytesRef lowerTerm, BytesRef upperTerm,
                         boolean includeLower, boolean includeUpper, String desc) {
    super(new LeftZeroPadTermRangeQuery(fieldName, lowerTerm, upperTerm, includeLower, includeUpper));
    this.desc = desc;
  }

  /** Returns the lower value of this range filter */
  public BytesRef getLowerTerm() { return query.getLowerTerm(); }

  /** Returns the upper value of this range filter */
  public BytesRef getUpperTerm() { return query.getUpperTerm(); }
  
  /** Returns <code>true</code> if the lower endpoint is inclusive */
  public boolean includesLower() { return query.includesLower(); }
  
  /** Returns <code>true</code> if the upper endpoint is inclusive */
  public boolean includesUpper() { return query.includesUpper(); }

  @Override
  public String toString() {
    if (desc == null) {
      return super.toString();
    } else {
      return desc;
    }
  }
}
