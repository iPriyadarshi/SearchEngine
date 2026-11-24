package com.searchengine.query.disk;

import com.searchengine.api.DocIdIterator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Boolean algebra over {@link DocIdIterator}s. Each combinator is itself an
 * iterator, so arbitrary AND / OR / NOT trees evaluate lazily, one document at
 * a time, without materializing any set of doc ids.
 */
public final class DocIdIterators {

    private DocIdIterators() {
    }

    public static DocIdIterator empty() {

        return new DocIdIterator() {

            private int doc = -1;

            @Override
            public int docId() {

                return doc;
            }

            @Override
            public int nextDoc() {

                return doc = NO_MORE_DOCS;
            }

            @Override
            public int advance(int target) {

                return doc = NO_MORE_DOCS;
            }

            @Override
            public long cost() {

                return 0;
            }
        };
    }

    /**
     * Documents present in every child. The rarest child leads and the others
     * are only {@code advance}d to its candidates ("leapfrog" intersection).
     */
    public static DocIdIterator and(List<? extends DocIdIterator> children) {

        if (children.isEmpty()) {

            return empty();
        }

        if (children.size() == 1) {

            return children.getFirst();
        }

        List<DocIdIterator> sorted = new ArrayList<>(children);

        sorted.sort(Comparator.comparingLong(DocIdIterator::cost));

        return new Conjunction(sorted);
    }

    public static DocIdIterator or(List<? extends DocIdIterator> children) {

        if (children.isEmpty()) {

            return empty();
        }

        if (children.size() == 1) {

            return children.getFirst();
        }

        return new Disjunction(new ArrayList<>(children));
    }

    /**
     * Every document id in {@code [0, maxDoc)} that {@code excluded} does not contain.
     */
    public static DocIdIterator not(DocIdIterator excluded, int maxDoc) {

        return new Complement(excluded, maxDoc);
    }

    private static final class Conjunction implements DocIdIterator {

        private final DocIdIterator lead;

        private final List<DocIdIterator> others;

        private int doc = -1;

        Conjunction(List<DocIdIterator> sortedByCost) {

            this.lead = sortedByCost.getFirst();

            this.others = sortedByCost.subList(1, sortedByCost.size());
        }

        @Override
        public int docId() {

            return doc;
        }

        @Override
        public int nextDoc() {

            return align(lead.nextDoc());
        }

        @Override
        public int advance(int target) {

            return align(lead.advance(target));
        }

        private int align(int candidate) {

            outer:
            while (candidate != NO_MORE_DOCS) {

                for (DocIdIterator other : others) {

                    int d = other.docId() < candidate ? other.advance(candidate) : other.docId();

                    if (d > candidate) {

                        candidate = lead.advance(d);

                        continue outer;
                    }
                }

                break;
            }

            return doc = candidate;
        }

        @Override
        public long cost() {

            return lead.cost();
        }
    }

    private static final class Disjunction implements DocIdIterator {

        private final List<DocIdIterator> children;

        private int doc = -1;

        Disjunction(List<DocIdIterator> children) {

            this.children = children;
        }

        @Override
        public int docId() {

            return doc;
        }

        @Override
        public int nextDoc() {

            return doc == NO_MORE_DOCS ? NO_MORE_DOCS : advance(doc + 1);
        }

        @Override
        public int advance(int target) {

            int min = NO_MORE_DOCS;

            for (DocIdIterator child : children) {

                int d = child.docId() < target ? child.advance(target) : child.docId();

                min = Math.min(min, d);
            }

            return doc = min;
        }

        @Override
        public long cost() {

            long sum = 0;

            for (DocIdIterator child : children) {

                sum += child.cost();
            }

            return sum;
        }
    }

    private static final class Complement implements DocIdIterator {

        private final DocIdIterator excluded;

        private final int maxDoc;

        private int doc = -1;

        Complement(DocIdIterator excluded, int maxDoc) {

            this.excluded = excluded;

            this.maxDoc = maxDoc;
        }

        @Override
        public int docId() {

            return doc;
        }

        @Override
        public int nextDoc() {

            return doc == NO_MORE_DOCS ? NO_MORE_DOCS : advance(doc + 1);
        }

        @Override
        public int advance(int target) {

            int candidate = Math.max(target, 0);

            while (candidate < maxDoc) {

                int skip = excluded.docId() < candidate ? excluded.advance(candidate) : excluded.docId();

                if (skip != candidate) {

                    return doc = candidate;
                }

                candidate++;
            }

            return doc = NO_MORE_DOCS;
        }

        @Override
        public long cost() {

            return maxDoc;
        }
    }
}
