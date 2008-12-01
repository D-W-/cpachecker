package cpa.common;

import java.util.List;

import cfa.objectmodel.CFANode;

import cpa.common.interfaces.AbstractElement;
import cpa.common.interfaces.AbstractElementWithLocation;
import cpa.common.CallStack;
import cpa.common.CompositeElement;

// AH: Added hack for AbstractElementWithLocation stuff
public class CompositeElement implements AbstractElementWithLocation
{
    private List<AbstractElement> elements;
    private CallStack callStack;
    
    public CompositeElement (List<AbstractElement> elements, CallStack stack)
    {
        this.elements = elements;
        this.callStack = stack;
    }
    
    public List<AbstractElement> getElements ()
    {
        return elements;
    }
    
    public int getNumberofElements(){
    	return elements.size();
    }
    
    public boolean equals (Object other)
    {
        if (other == this)
            return true;
        
        if (!(other instanceof CompositeElement))
            return false;
        
        CompositeElement otherComposite = (CompositeElement) other;
        List<AbstractElement> otherElements = otherComposite.elements;
        
        
        if (otherElements.size () != this.elements.size ())
            return false;
        
        for (int idx = 0; idx < elements.size (); idx++)
        {
            AbstractElement element1 = otherElements.get (idx);
            AbstractElement element2 = this.elements.get (idx);
            
            if (!element1.equals (element2))
                return false;
        }
        
        if(!otherComposite.getCallStack().equals(this.getCallStack())){
        	return false;
        }
        
        return true;
    }
    
    public String toString ()
    {
        StringBuilder builder = new StringBuilder ();
        builder.append ('(');
        for (AbstractElement element : elements)
            builder.append (element.toString ()).append (',');
        builder.replace (builder.length () - 1, builder.length (), ")");
        
        return builder.toString ();
    }

	public AbstractElement get(int idx) {
		return elements.get(idx);
	}

	public CallStack getCallStack() {
		return callStack;
	}

	public void setCallStack(CallStack callStack) {
		this.callStack = callStack;
	}

	@Override
	public CFANode getLocationNode() {
		// AH: yes, it is a hack
		AbstractElement firstElementTmp = get(0);
		AbstractElementWithLocation firstElement = (AbstractElementWithLocation)firstElementTmp;
		
		return firstElement.getLocationNode();
	}
}
