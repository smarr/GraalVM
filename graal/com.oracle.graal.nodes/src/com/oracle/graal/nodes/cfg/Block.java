/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.nodes.cfg;

import java.util.*;

import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;

public final class Block {

    protected final BeginNode beginNode;

    protected int id;

    protected FixedNode endNode;
    protected Loop loop;

    protected List<Block> predecessors;
    protected List<Block> successors;

    protected Block dominator;
    protected List<Block> dominated;
    protected Block postdominator;

    private boolean align;
    private int linearScanNumber;

    protected Block(BeginNode node) {
        this.beginNode = node;

        this.id = ControlFlowGraph.BLOCK_ID_INITIAL;
        this.linearScanNumber = -1;
    }

    public int getId() {
        return id;
    }

    public BeginNode getBeginNode() {
        return beginNode;
    }

    public FixedNode getEndNode() {
        return endNode;
    }

    public Loop getLoop() {
        return loop;
    }

    public int getLoopDepth() {
        return loop == null ? 0 : loop.depth;
    }

    public boolean isLoopHeader() {
        return getBeginNode() instanceof LoopBeginNode;
    }

    public boolean isLoopEnd() {
        return getEndNode() instanceof LoopEndNode;
    }

    public boolean isExceptionEntry() {
        return getBeginNode() instanceof ExceptionObjectNode;
    }

    public Block getFirstPredecessor() {
        return predecessors.get(0);
    }

    public List<Block> getPredecessors() {
        return predecessors;
    }

    public Block getFirstSuccessor() {
        return successors.get(0);
    }

    public List<Block> getSuccessors() {
        return successors;
    }

    public Block getDominator() {
        return dominator;
    }

    public Block getEarliestPostDominated() {
        Block b = this;
        while (true) {
            Block dom = b.getDominator();
            if (dom != null && dom.getPostdominator() == b) {
                b = dom;
            } else {
                break;
            }
        }
        return b;
    }

    public List<Block> getDominated() {
        if (dominated == null) {
            return Collections.emptyList();
        }
        return dominated;
    }

    public Block getPostdominator() {
        return postdominator;
    }

    private class NodeIterator implements Iterator<FixedNode> {

        private FixedNode cur;

        public NodeIterator() {
            cur = getBeginNode();
        }

        @Override
        public boolean hasNext() {
            return cur != null;
        }

        @Override
        public FixedNode next() {
            FixedNode result = cur;
            if (cur == getEndNode()) {
                cur = null;
            } else {
                cur = ((FixedWithNextNode) cur).next();
            }
            assert !(cur instanceof BeginNode);
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public Iterable<FixedNode> getNodes() {
        return new Iterable<FixedNode>() {

            @Override
            public Iterator<FixedNode> iterator() {
                return new NodeIterator();
            }

            @Override
            public String toString() {
                StringBuilder str = new StringBuilder().append('[');
                for (FixedNode node : this) {
                    str.append(node).append(", ");
                }
                if (str.length() > 1) {
                    str.setLength(str.length() - 2);
                }
                return str.append(']').toString();
            }
        };
    }

    @Override
    public String toString() {
        return "B" + id;
    }

    public int getPredecessorCount() {
        return getPredecessors().size();
    }

    public int getSuccessorCount() {
        return getSuccessors().size();
    }

    public boolean dominates(Block block) {
        return block.isDominatedBy(this);
    }

    public boolean isDominatedBy(Block block) {
        if (block == this) {
            return true;
        }
        if (dominator == null) {
            return false;
        }
        return dominator.isDominatedBy(block);
    }

    public int getLinearScanNumber() {
        return linearScanNumber;
    }

    public void setLinearScanNumber(int linearScanNumber) {
        this.linearScanNumber = linearScanNumber;
    }

    public boolean isAligned() {
        return align;
    }

    public void setAlign(boolean align) {
        this.align = align;
    }
}
