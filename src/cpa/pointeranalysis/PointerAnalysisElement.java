/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker. 
 *
 *  Copyright (C) 2007-2008  Dirk Beyer and Erkan Keremoglu.
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
 *    http://www.cs.sfu.ca/~dbeyer/CPAchecker/
 */
package cpa.pointeranalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import cfa.objectmodel.CFAEdge;
import cpa.common.interfaces.AbstractElement;
import cpa.pointeranalysis.Pointer.PointerOperation;

/**
 * This class is the abstraction of the memory of the program (global variables,
 * local variables, heap).
 * 
 * @author Philipp Wendler
 */
public class PointerAnalysisElement implements AbstractElement, Memory {

  private static final char FUNCTION_NAME_SEPARATOR = ':';

  private CFAEdge currentEdge = null;
  
  private final Map<String, Map<String, Pointer>> stack;
  // global variables are stored here with function name ""
  // non-pointer variables are tracked too, (mapped to null)

  private String currentFunctionName = "";
  
  private final HashMap<MemoryAddress, Pointer> heap;
  
  private final HashSet<MemoryRegion> mallocs; // list of all malloc'ed memory regions

  private final HashMap<PointerTarget, Set<PointerLocation>> reverseRelation; // reverse mapping from pointer targets to pointer locations
  
  private final HashMap<PointerLocation, Set<PointerLocation>> aliases; // mapping from pointer location to locations of all aliases
  
  private final HashSet<PointerLocation> tempTracked; // non-pointer variables temporarily containing pointer values
  
  /*
   * Following possibilities exist:
   * Malloc and Pointer: a malloc with unknown length,
   *        ASTNode contains parameter to malloc
   * Pointer: a pointer variable with unknown type was declared,
   *        ASTNode contains type specifier
   * Pointer, PointerBackup, OffsetNegative: an unknown offset was added to a pointer,
   *        Pointer is the new one, PointerBackup is the old one (before shifting),
   *        and ASTNode is the shift operand   
   */
  
  public PointerAnalysisElement() {
    stack = new HashMap<String, Map<String, Pointer>>();
    heap = new HashMap<MemoryAddress, Pointer>();
    mallocs = new HashSet<MemoryRegion>();
    reverseRelation = new HashMap<PointerTarget, Set<PointerLocation>>();
    aliases = new HashMap<PointerLocation, Set<PointerLocation>>();
    tempTracked = new HashSet<PointerLocation>();
    
    stack.put("", new HashMap<String, Pointer>());
    callFunction("main");
  }
  
  private PointerAnalysisElement(final Map<String, Map<String, Pointer>> stack,
                                 final Map<MemoryAddress, Pointer> heap,
                                 final Set<MemoryRegion> mallocs,
                                 final Map<PointerTarget, Set<PointerLocation>> reverseRelation,
                                 final Map<PointerLocation, Set<PointerLocation>> aliases,
                                 final Set<PointerLocation> tempTracked,
                                 final String currentFunctionName) {
    this.stack = new HashMap<String, Map<String, Pointer>>();
    this.heap = new HashMap<MemoryAddress, Pointer>();
    this.mallocs = new HashSet<MemoryRegion>(mallocs);
    this.reverseRelation = new HashMap<PointerTarget, Set<PointerLocation>>();
    this.aliases = new HashMap<PointerLocation, Set<PointerLocation>>();
    this.currentFunctionName = currentFunctionName;
    this.tempTracked = new HashSet<PointerLocation>(tempTracked);

    for (String function : stack.keySet()) {
      Map<String, Pointer> oldPointers = stack.get(function);
      Map<String, Pointer> newPointers = new HashMap<String, Pointer>();
      
      for (String var : oldPointers.keySet()) {
        Pointer oldPointer = oldPointers.get(var);
        newPointers.put(var, oldPointer == null ? null : oldPointer.clone());
      }
      this.stack.put(function, newPointers);
    }
    
    for (MemoryAddress memAddress : heap.keySet()) {
      this.heap.put(memAddress, heap.get(memAddress).clone());
    }
    
    for (PointerTarget target : reverseRelation.keySet()) {
      this.reverseRelation.put(target, new HashSet<PointerLocation>(reverseRelation.get(target)));
    }
    
    for (Set<PointerLocation> aliasSet : aliases.values()) {
      Set<PointerLocation> newAliasSet = new HashSet<PointerLocation>(aliasSet);
      for (PointerLocation location : newAliasSet) {
        this.aliases.put(location, newAliasSet);
      }
    }
    
    sanityCheck();
  }
  
  private void sanityCheck() throws IllegalStateException {
    for (Set<PointerLocation> aliasSet : aliases.values()) {
      for (PointerLocation loc : aliasSet) {
       if (aliases.get(loc) != aliasSet) {
         throw new IllegalStateException("Aliases wrong");
       }
      }
    }
    
    for (PointerTarget target : reverseRelation.keySet()) {
      for (PointerLocation loc : reverseRelation.get(target)) {
        Pointer p = getPointer(loc);
        if (p == null) {
          throw new IllegalStateException("Reverse relation " + loc + " <- "
                                          + target + " but there is no pointer!");
        }
        if (!p.contains(target)) {
          throw new IllegalStateException("Reverse relation " + loc + " <- "
                                        + target + " without forward relation!");
        }
      }
    }
    
    for (Map<String, Pointer> pointers : stack.values()) {
      for (Pointer p : pointers.values()) {
        if (p == null) {
          continue; // a non-pointer variable
        }
        if (p.getNumberOfTargets() == 0) {
          throw new IllegalStateException("Pointer " + p.getLocation() + " has no targets!");
        }
        for (PointerTarget target : p.getTargets()) {
          if (target != UNKNOWN_POINTER && target != INVALID_POINTER) {
            if (reverseRelation.get(target) == null) {
              throw new IllegalStateException("Target " + target + " without reverse relations!");
            }
            if (!reverseRelation.get(target).contains(p.getLocation())) {
              throw new IllegalStateException("Forward relation " + p.getLocation()
                            + " -> " + target + " without reverse relation!");
            }
          }
        }
        PointerLocation loc = p.getLocation();
        if (!(loc instanceof LocalVariable) && !(getPointer(loc) == p) ) {
          throw new IllegalStateException("Pointer in invalid location!");
        }
      }
    }
    for (Pointer p : heap.values()) {
      if (p.getNumberOfTargets() == 0) {
        throw new IllegalStateException("Pointer " + p.getLocation() + " has no targets!");
      }
      for (PointerTarget target : p.getTargets()) {
        if (target != UNKNOWN_POINTER && target != INVALID_POINTER) {
          if (reverseRelation.get(target) == null) {
            throw new IllegalStateException("Target without reverse relations");
          }
          if (!reverseRelation.get(target).contains(p.getLocation())) {
            throw new IllegalStateException("Forward relation " + p.getLocation()
                            + " -> " + target + " without reverse relation!");
          }
        }
      }
      
      PointerLocation loc = p.getLocation();
      if (!(loc instanceof MemoryAddress) && !(getPointer(loc) == p) ) {
        throw new IllegalStateException("Pointer in invalid location!");
      }
    }
    
    for (PointerLocation loc : tempTracked) {
      if (loc.getPointer(this) == null) {
        throw new IllegalStateException("Temporarily tracked non-pointer variable "
                                        + loc + " without content!");
      }
    }
  }
  
  private Map<String, Pointer> getGlobalPointers() {
    return stack.get("");
  }
  
  private Map<String, Pointer> getLocalPointers() {
    return stack.get(currentFunctionName);
  }
  
  private void registerPointer(Pointer p, PointerLocation loc) {
    assert p != null && loc != null;
    p.setLocation(loc);
    addAllReverseRelations(p);
    findAndMergePossibleAliases(p);
  }
  
  @Override
  public void addNewGlobalPointer(String name, Pointer p) {
    // p may be null, then name is a non-pointer variable
    assert name != null;
    getGlobalPointers().put(name, p);
    if (p != null) {
      registerPointer(p, new GlobalVariable(name));
    }
  }

  @Override
  public void addNewLocalPointer(String name, Pointer p) {
    // p may be null, then name is a non-pointer variable
    assert name != null;
    getLocalPointers().put(name, p);
    if (p != null) {
      registerPointer(p, new LocalVariable(getCurrentFunctionName(), name));
    }
  }
  
  public void addTemporaryTracking(String var, Pointer content) {
    PointerLocation loc = lookupVariable(var);
    if (loc == null) {
      throw new IllegalStateException("Unknown variable " + var);
    }
    
    if (tempTracked.contains(loc)) {
      pointerOp(new Pointer.Assign(content), getPointer(loc));

    } else {
      tempTracked.add(loc);
      Pointer p = new Pointer(content.getLevelOfIndirection());
      if (loc instanceof LocalVariable) {
        getLocalPointers().put(var, p);
      } else if (loc instanceof GlobalVariable) {
        getGlobalPointers().put(var, p);
      } else {
        throw new IllegalStateException();
      }
      
      p.setLocation(loc);
      addAllReverseRelations(p); // necessary, else assign will throw exception
      pointerOp(new Pointer.Assign(content), p);
    }
  }
  
  public void removeTemporaryTracking(PointerLocation loc) {
    if (!tempTracked.contains(loc)) {
      throw new IllegalStateException("Variable " + loc + " does not contain a pointer!");
    }
    
    tempTracked.remove(loc);
    Pointer p = getPointer(loc);
    removeAllAliases(loc);
    removeAllReverseRelations(p);
    
    if (loc instanceof LocalVariable) {
      getLocalPointers().remove(((LocalVariable)loc).getVarName());
    } else if (loc instanceof GlobalVariable) {
      getGlobalPointers().remove(((GlobalVariable)loc).getVarName());
    } else {
      throw new IllegalStateException();
    }
  }
  
  public boolean isPointerVariable(PointerLocation loc) {
    return (!tempTracked.contains(loc) && getPointer(loc) != null);
  }
  
  @Override
  public Pointer lookupPointer(String name) {
    Pointer result = getGlobalPointers().get(name);
    if (result == null) {
      result = getLocalPointers().get(name);
    }
    return result;
  }
  
  @Override
  public Variable lookupVariable(String name) {
    if (getLocalPointers().containsKey(name)) {
      return new LocalVariable(getCurrentFunctionName(), name);
      
    } else if (getGlobalPointers().containsKey(name)) {
      return new GlobalVariable(name);
    
    } else {
      throw new IllegalStateException("Unknown variable " + name);
      // unknown variable has to be a local variable, as we store all global
      // variables (not just global pointer variables) in the globalPointers map 
      //return new LocalVariable(getCurrentFunctionName(), name);
    }
  }
  
  @Override
  public Pointer getPointer(LocalVariable var) {
    Map<String, Pointer> stackframe = stack.get(var.getFunctionName());
    assert stackframe != null;
    return stackframe.get(var.getVarName());
  }

  @Override
  public Pointer getPointer(GlobalVariable var) {
    return getGlobalPointers().get(var.getVarName());
  }
  
  @Override
  public Pointer getPointer(MemoryAddress memAddress) {
    return heap.get(memAddress);
  }
    
  private Pointer getPointer(PointerLocation location) {
    return location.getPointer(this);
  }
  
  @Override
  public void writeOnHeap(MemoryAddress memAddress, Pointer p) {
    assert memAddress != null && p != null;
    heap.put(memAddress, p);
    registerPointer(p, memAddress);
  }

  
  private void addAllReverseRelations(Pointer pointer) {
    PointerLocation location = pointer.getLocation();
    
    for (PointerTarget target : pointer.getTargets()) {
      
      if (target != INVALID_POINTER && target != UNKNOWN_POINTER) {
        
        Set<PointerLocation> locs = reverseRelation.get(target);
        if (locs == null) {
          locs = new HashSet<PointerLocation>();
          reverseRelation.put(target, locs);
        }
        
        locs.add(location);
      }
    }
  }
  
  private void removeAllReverseRelations(Pointer pointer) {
    PointerLocation location = pointer.getLocation();

    for (PointerTarget target : pointer.getTargets()) {
      
      if (target != INVALID_POINTER && target != UNKNOWN_POINTER) {
        
        Set<PointerLocation> locs = reverseRelation.get(target);
        if (locs == null || !locs.contains(location)) {
          throw new IllegalStateException("Trying to remove reverse reference to location "
                  + location + " from target " + target + ", but it's not there");
        }
        
        locs.remove(location);
      }
    }
  }
  
  @Override
  public void makeAlias(PointerLocation firstPointer, PointerLocation secondPointer) {
    Set<PointerLocation> newAliases = aliases.get(firstPointer);
    if (newAliases == null) {
      newAliases = new HashSet<PointerLocation>();
      newAliases.add(firstPointer);
      aliases.put(firstPointer, newAliases);
    }
    
    Set<PointerLocation> oldAliases = aliases.get(secondPointer);
    if (oldAliases != null) {
      oldAliases.remove(secondPointer);
    }
    
    newAliases.add(secondPointer);
    aliases.put(secondPointer, newAliases);
  }
  
  /**
   * Merge the alias lists of both pointers. This does not change the actual list of targets.
   * 
   * @param firstPointer
   * @param secondPointer
   */
  private void mergeAliases(PointerLocation firstPointer, PointerLocation secondPointer) {
    Set<PointerLocation> firstAliases = aliases.get(firstPointer);
    
    if (firstAliases == null || firstAliases.size() == 1) {
      makeAlias(secondPointer, firstPointer);

    } else {
      Set<PointerLocation> secondAliases = aliases.get(secondPointer);
      
      if (firstAliases != secondAliases) {
        if (secondAliases == null || secondAliases.size() == 1) {
          makeAlias(firstPointer, secondPointer);
        
        } else {
          // both pointers have aliases
          for (PointerLocation p : secondAliases) {
            firstAliases.add(p);
            aliases.put(p, firstAliases);
          }
          secondAliases.clear(); // just for safety, there should be no references to this object anymore
        }
      }
    }
  }
  
  private void removeAllAliases(PointerLocation pointer) {
    Set<PointerLocation> pointerAliases = aliases.get(pointer);
    if (pointerAliases != null && pointerAliases.size() > 1) {
      pointerAliases.remove(pointer);
      aliases.remove(pointer);
    }
  }
  
  private Set<PointerLocation> getAliases(PointerLocation pointer) {
    Set<PointerLocation> pointerAliases = aliases.get(pointer);

    if (pointerAliases == null) {
      pointerAliases = new HashSet<PointerLocation>();
      pointerAliases.add(pointer);
      aliases.put(pointer, pointerAliases);
    
    } else {
      assert pointerAliases.contains(pointer);
    } 

    //if (getPointer(pointer).getNumberOfTargets() == 1) {
      // TODO: there could be other pointers with the same target -> they are aliases, too
    //}
    
    return Collections.unmodifiableSet(pointerAliases);
  }
  
  @Override
  public boolean areAliases(Pointer p1, Pointer p2) {
    if (getAliases(p1.getLocation()).contains(p2.getLocation())) {
      return true;
    }
    
    // check if both pointer have the same single target
    if (p1.getNumberOfTargets() == 1 && p2.getNumberOfTargets() == 1) {
      PointerTarget target = p1.getFirstTarget();
      
      if (target.equals(p2.getFirstTarget())
          && target != INVALID_POINTER && target != UNKNOWN_POINTER) {
        
        mergeAliases(p1.getLocation(), p2.getLocation());
        System.out.println("INFO: Found pointer aliases which were not already aliased.");
        return true;
      }
    }
    
    return false;
  }
  
  private void findAndMergePossibleAliases(Pointer p) {
    if (p.getNumberOfTargets() != 1) {
      // not possible to decide whether another pointer is a true alias
      return;
    }
    
    PointerLocation loc = p.getLocation();
    PointerTarget target = p.getFirstTarget();
    if (target == INVALID_POINTER || target == UNKNOWN_POINTER) {
      // no tracking of aliases for these values
      return;
    }
    
    Set<PointerLocation> aliasesOfP = getAliases(loc);
    if (!reverseRelation.containsKey(target)) {
      throw new IllegalStateException("Target " + target + " has no set of locations!");
    }
        
    for (PointerLocation candidateLoc : reverseRelation.get(target)) {
      if (!aliasesOfP.contains(candidateLoc)) {
        Pointer candidatePointer = getPointer(candidateLoc);
        
        if (candidatePointer.getNumberOfTargets() == 1) {
          assert candidatePointer.getTargets().equals(p.getTargets());
          
          mergeAliases(loc, candidateLoc);
          aliasesOfP = getAliases(loc); // this may be a different object now
        }
      }
    }
  }
      
  @Override
  public MemoryAddress malloc() {
    MemoryRegion mem = new MemoryRegion();
    mallocs.add(mem);
    return new MemoryAddress(mem);
  }
  
  @Override
  public void free(MemoryRegion mem) throws InvalidPointerException {
    if (!mallocs.contains(mem)) {
      throw new InvalidPointerException("Double free of region " + mem);
    }
    mallocs.remove(mem);
    // TODO: assign INVALID_POINTER to all pointers pointing to this region?
  }
  
  @Override
  public Pointer deref(Pointer pointer, PointerTarget target) {
    assert pointer.isPointerToPointer();
    assert pointer.contains(target);
    
    Pointer result;
    
    if (target instanceof PointerLocation) {
      result = ((PointerLocation)target).getPointer(this);
    
      if (result == null) {
        if (target instanceof MemoryAddress) {
          // result == null is OK because heap is initialized lazily
          // assume, the heap is full of NULL_POINTERs where nothing has been
          // written
          result = new Pointer(pointer.getLevelOfIndirection()-1);
          writeOnHeap((MemoryAddress)target, result);
        
        } else {
          // result == null is not OK, because source pointer is a pointer to pointer
          throw new IllegalStateException("Trying to dereference target " + target + ", but it does not contain a pointer!");
        }
      }
      return result;
      
    } else if (target == NULL_POINTER || target == INVALID_POINTER) {
      // warning is printed elsewhere
      return null;
    
    } else if (target == UNKNOWN_POINTER) {
      return null;
    
    } else {
      throw new UnsupportedOperationException("Missing implementation to dereference the target " + target);
    }
  }
  
  public void pointerOp(PointerOperation op, Pointer pointer) {
    pointerOpNoDereference(op, pointer, false);
  }
  
  public void pointerOp(PointerOperation op, Pointer pointer, boolean dereferenceFirst) {
    if (pointer == null) {
      return;
    }
    
    if (dereferenceFirst) {
      boolean keepOldTargets = (pointer.getNumberOfTargets() != 1);
      
      if (!pointer.isPointerToPointer()) {
        throw new IllegalArgumentException("Pointers which do not point to other pointers cannot be dereferenced in this analysis!");
      }
      
      if (!pointer.isDereferencable()) {
        throw new IllegalArgumentException("Unsafe deref of pointer " + pointer.getLocation() + " = " + pointer);
      }
      
      for (PointerTarget target : pointer.getTargets()) {
        Pointer actualPointer = deref(pointer, target);
        
        pointerOpNoDereference(op, actualPointer, keepOldTargets);
      }
      
    } else {
      pointerOpNoDereference(op, pointer, false);
    }
  }

  private void pointerOpNoDereference(PointerOperation op, Pointer pointer, boolean keepOldTargets) {
    if (pointer == null) {
      return;
    }
    
    PointerLocation location = pointer.getLocation();
    
    removeAllReverseRelations(pointer);
    removeAllAliases(location);

    op.doOperation(this, pointer, keepOldTargets);
    
    addAllReverseRelations(pointer);
    findAndMergePossibleAliases(pointer);
  }
  
  private void pointerOpForAllAliases(PointerOperation op, Pointer pointer, boolean keepOldTargets) {
    
    for (PointerLocation aliasLoc : getAliases(pointer.getLocation())) {
      Pointer aliasPointer = getPointer(aliasLoc);
      removeAllReverseRelations(aliasPointer);
      
      op.doOperation(this, aliasPointer, keepOldTargets);
      
      addAllReverseRelations(aliasPointer);
    }
    // do not call findAndMergePossibleAliases here because this method should
    // leave the alias set untouched
  }
    
  public void pointerOpAssumeEquality(Pointer firstPointer, Pointer secondPointer) {
    if (areAliases(firstPointer, secondPointer)) {
      return;
    }
    
    ArrayList<PointerTarget> intersection = new ArrayList<PointerTarget>();
    Set<PointerTarget> firstTargets = firstPointer.getTargets();
    Set<PointerTarget> secondTargets = secondPointer.getTargets();

    if (firstTargets.contains(Memory.UNKNOWN_POINTER)) {
      intersection.addAll(secondTargets);
    }
    if (secondTargets.contains(Memory.UNKNOWN_POINTER)) {
      intersection.addAll(firstTargets);
    }
    
    for (PointerTarget target : firstTargets) {
      if (secondTargets.contains(target)) {
        intersection.add(target);
      }
    }

    PointerOperation op = new Pointer.AssignListOfTargets(intersection);
    pointerOpForAllAliases(op, firstPointer, false);
    pointerOpForAllAliases(op, secondPointer, false);
    
    // now first and second pointer have the same set of targets
    
    mergeAliases(firstPointer.getLocation(), secondPointer.getLocation());
  }
  
  public void pointerOpAssumeEquality(Pointer pointer, PointerTarget target) {
    if (!(pointer.getNumberOfTargets() == 1 && pointer.contains(target))) {
      
      pointerOpForAllAliases(new Pointer.Assign(target), pointer, false);
      
      findAndMergePossibleAliases(pointer);
    } else {
      // it is already equal like it should be 
    }
  }
  
  public void pointerOpAssumeInequality(Pointer firstPointer, Pointer secondPointer) {
    if (areAliases(firstPointer, secondPointer)) {
      throw new IllegalStateException("Aliased pointers cannot be inequal.");
    }
    
    if (firstPointer.getNumberOfTargets() == 1) {
      pointerOpAssumeInequality(secondPointer, firstPointer.getFirstTarget());
    
    } else if (secondPointer.getNumberOfTargets() == 1) {
      pointerOpAssumeInequality(firstPointer, secondPointer.getFirstTarget());
      
    } else {
      // can't do anything
    }
  }

  public void pointerOpAssumeInequality(Pointer pointer, PointerTarget target) {
    if (target != INVALID_POINTER && target != UNKNOWN_POINTER) { 
      
      if (pointer.contains(target)) {
        pointerOpForAllAliases(new Pointer.AssumeInequality(target), pointer, true);

        findAndMergePossibleAliases(pointer);
      }
    }
  }
  
  @Override
  public Set<MemoryRegion> checkMemoryLeak() {
    Set<MemoryRegion> unmarkedRegions = new HashSet<MemoryRegion>(mallocs);
    
    boolean unknown;
    for (Map<String, Pointer> stackframe : stack.values()) {
      for (Pointer p: stackframe.values()) {
        unknown = checkMemoryLeak(unmarkedRegions, p);
        if (unknown) {
          unmarkedRegions.clear();
          return unmarkedRegions;
        }
      }
    }
    
    return unmarkedRegions;
  }
  
  private boolean checkMemoryLeak(Set<MemoryRegion> unmarkedRegions, Pointer p) {
    for (PointerTarget target : p.getTargets()) {
      if (target == Memory.UNKNOWN_POINTER) {
        // if there is one unknown pointer, we cannot say anything about memory leaks
        return true;  
      
      } else if (target instanceof MemoryAddress) {
        MemoryRegion memRegion = ((MemoryAddress)target).getRegion();
        boolean unmarked = unmarkedRegions.contains(memRegion);
        if (unmarked) {
          unmarkedRegions.remove(memRegion);
          
          // recursively mark on heap
          for (MemoryAddress heapMem : heap.keySet()) {
            if (unmarkedRegions.contains(heapMem.getRegion())
                && heapMem.getRegion() == memRegion) {
              
              checkMemoryLeak(unmarkedRegions, heap.get(heapMem));
            }
          }
          
        }
      }
    }
    return false;
  }
  
  @Override
  public boolean equals(Object other) {
    if (!(other instanceof PointerAnalysisElement)) {
      return false;
    }
    
    PointerAnalysisElement otherElement = (PointerAnalysisElement)other;
    
    return stack.equals(otherElement.stack)
        && reverseRelation.equals(otherElement.reverseRelation)
        && aliases.equals(otherElement.aliases);
  }
  
  public void callFunction(String functionName) {
    //HashMap<String, Pointer> newLocalPointers = ;
    //localPointers.addLast(new Pair<String, HashMap<String, Pointer>>(functionName, newLocalPointers));
    
    if (currentFunctionName.equals("")) {
      currentFunctionName = functionName;
    } else {
      currentFunctionName = currentFunctionName + FUNCTION_NAME_SEPARATOR + functionName;
    }
    stack.put(currentFunctionName, new HashMap<String, Pointer>());
  }
  
  public void returnFromFunction() {
    assert currentFunctionName != "" && currentFunctionName.contains(":")
        : "Cannot return from global context or main function!";
    //localPointers.pollLast();
    String oldFunctionName = currentFunctionName;
    currentFunctionName = currentFunctionName.substring(0, currentFunctionName.lastIndexOf(FUNCTION_NAME_SEPARATOR));

    Iterator<PointerLocation> tempIt = tempTracked.iterator();
    while (tempIt.hasNext()) {
      PointerLocation loc = tempIt.next();
      
      if (oldFunctionName.equals(((LocalVariable)loc).getFunctionName())) {
        tempIt.remove(); // instead of tempTracked.remove(loc);
      }
    }
    
    // remove all pointers in local variables from aliases
    Iterator<PointerLocation> aliasIt = aliases.keySet().iterator();
    while (aliasIt.hasNext()) {
      PointerLocation loc = aliasIt.next();
      
      if (loc instanceof LocalVariable) {
      
        if (oldFunctionName.equals(((LocalVariable)loc).getFunctionName())) {
          // a local pointer is aliased to another pointer
          aliases.get(loc).remove(loc);
          aliasIt.remove(); // instead of aliases.remove(loc);
        }
      }
    }
    
    Iterator<PointerTarget> reverseIt = reverseRelation.keySet().iterator();
    while (reverseIt.hasNext()) {
      PointerTarget target = reverseIt.next();
      
      // remove all pointers in local variables from reverse relation
      Set<PointerLocation> locations = reverseRelation.get(target);
      Iterator<PointerLocation> itLocations = locations.iterator();
      while (itLocations.hasNext()) {
        PointerLocation loc = itLocations.next();
        if (loc instanceof LocalVariable) {
          if (oldFunctionName.equals(((LocalVariable)loc).getFunctionName())) {
            // a local pointer points to this target
            itLocations.remove();
          }
        }
      }
      
      // remove all pointers to local variables from reverse relation 
      if (target instanceof LocalVariable) {
        if (oldFunctionName.equals(((LocalVariable)target).getFunctionName())) {
          // this target is a local variable, there may be no remaining reference to this target!
          while (!locations.isEmpty()) {
            PointerLocation loc = locations.toArray(new PointerLocation[0])[0];
            Pointer p = loc.getPointer(this);

            // all local locations have already been removed, there has to be a global one!
            PointerAnalysisTransferRelation.addWarning("After returning from "
                + oldFunctionName + ", a reference to a local variable remains in the pointers "
                + getAliases(loc) + " = " + p, getCurrentEdge(), loc.toString());
            
            // add INVALID_POINTER and remove this target
            pointerOpForAllAliases(new Pointer.Assign(INVALID_POINTER), p, true);
            pointerOpAssumeInequality(p, target);
          }
          reverseIt.remove();
        }
      }
    }
    
    // do this at the end of this method to make getPointer(LocalVariable) still work
    stack.remove(oldFunctionName); 
  }
  
  public String getCurrentFunctionName() {
    return currentFunctionName;
  }
  
  public void setCurrentEdge(CFAEdge currentEdge) {
    this.currentEdge = currentEdge;
  }

  public CFAEdge getCurrentEdge() {
    return currentEdge;
  }

  @Override
  public int hashCode() {
    return stack.hashCode();
  }
  
  @Override
  public PointerAnalysisElement clone() {
    return new PointerAnalysisElement(stack, heap, mallocs, reverseRelation,
                                      aliases, tempTracked, currentFunctionName); 
  }
  
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("[<global:");
    for (String var : getGlobalPointers().keySet()) {
      sb.append(" " + var + "=" + getGlobalPointers().get(var) + " ");
    }
    for (String function : stack.keySet()) {
      if (!function.equals("")) {
        sb.append("> <" + function + ":");
        Map<String, Pointer> pointers = stack.get(function);
        for (String var : pointers.keySet()) {
          sb.append(" " + var + "=" + pointers.get(var) + " ");
        }
      }
    }
    sb.append("> <heap:");
    for (MemoryAddress memAddress : heap.keySet()) {
      sb.append(" " + memAddress + "=" + heap.get(memAddress));
    }
    sb.append(">]");
    return sb.toString();
  }
}