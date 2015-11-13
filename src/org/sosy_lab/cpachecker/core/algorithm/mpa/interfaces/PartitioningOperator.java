/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.mpa.interfaces;

import java.util.Comparator;
import java.util.Set;

import org.sosy_lab.cpachecker.core.interfaces.Property;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public interface PartitioningOperator {

  public static class PartitioningException extends Exception {
    private static final long serialVersionUID = -2327879006682138193L;

    public PartitioningException(String pMessage) {
      super(pMessage);
    }
  }

  public ImmutableList<ImmutableSet<Property>> partition(
      ImmutableList<ImmutableSet<Property>> pLastCheckedPartitioning,
      Set<Property> pToCheck,
      Set<Property> pDisabledProperties,
      Comparator<Property> pPropertyExpenseComparator) throws PartitioningException;

}