/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2012  Dirk Beyer
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
package org.sosy_lab.cpachecker.util.invariants.balancer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;

import org.sosy_lab.common.LogManager;

/*
 * Handles a series of substitutions, automatically applying them in order of lowest degree first,
 * and applying each one to all the others, at the time that it is applied.
 * For example, if it is constructed on the two substitutions
 *  p1 <-- p2
 *  p1 <-- p3
 * the second will be useful even after the first is applied, since the first will automatically be
 * applied to the second, giving
 *  p2 <-- p3.
 *
 * Can serve up substitutions one after another so that you can apply them yourself, or can
 * automatically apply them to various structures, such as polynomials, rational functions,
 * and matrices.
 */
public class SubstitutionManager {

  private final LogManager logger;
  private final Vector<Substitution> subList;

  public SubstitutionManager(Collection<Substitution> s, LogManager lm) {
    subList = new Vector<Substitution>(s);
    logger = lm;
  }

  /*
   * Apply all the substitutions to the matrix.
   */
  public void applyAll(Matrix mat) {
    while (hasNext()) {
      Substitution subs = next();
      mat.applySubstitution(subs);
    }
  }

  public boolean hasNext() {
    return subList.size() > 0;
  }

  public Substitution next() {
    if (!hasNext()) {
      return null;
    }
    logger.log(Level.ALL,"Substitutions:\n", subList);
    Substitution s = subList.get(0);
    // Now we apply s to the remaining substitutions.
    Set<Substitution> toRemove = new HashSet<Substitution>();
    // We will remove the one we just popped off the front of the list.
    toRemove.add(s);
    // Now consider the rest (if there are any more).
    for (int i = 1; i < subList.size(); i++) {
      // Get the next substitution.
      Substitution t = subList.get(i);
      // Apply s to it.
      Substitution u = t.applySubstitution(s);
      if (u == null) {
        // If this nullified the substitution, then we'll get rid of it.
        toRemove.add(t);
      } else {
        // Else we replace it by the result.
        subList.set(i, u);
      }
    }
    // Remove those that were nullified.
    subList.removeAll(toRemove);
    // Sort into ascending order according to degree of rhs.
    Collections.sort(subList);
    //logger.log(Level.ALL,"Substitutions:\n", subList);
    // Finally, return s.
    return s;
  }

}
