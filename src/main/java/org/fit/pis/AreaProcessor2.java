package org.fit.pis;

import gnu.trove.TIntProcedure;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.infomatiq.jsi.Rectangle;
import com.infomatiq.jsi.SpatialIndex;
import com.infomatiq.jsi.rtree.RTree;

class AreaMatch implements TIntProcedure
{
    private final ArrayList<Integer> ids;

    public AreaMatch()
    {
        this.ids = new ArrayList<Integer>();
    }

    @Override
    public boolean execute(int id) {
        ids.add(id);
        return true;
    }

    public ArrayList<Integer> getIds() {
        return ids;
    }
};

public class AreaProcessor2
{
    private static final boolean DEBUG = true;
    private final ArrayList<PageArea> areas;

    private final SpatialIndex areaTree;
    private final SpatialIndex groupTree;

    private final HashSet<PageArea> groups;
    private final ArrayList<PageArea> ungrouped;

    public static final double similarityThreshold = 0.1;

    private final int pageWidth;
    private final int pageHeight;

    private final StopWatch time;

    private BufferedWriter log;

    public AreaProcessor2(ArrayList<PageArea> areas, int width, int height) throws IOException
    {
        Collections.sort(areas, new AreaSizeComparator());
        /* Note: we store only leaf areas */
        this.areas = new ArrayList<PageArea>();
        this.areaTree = new RTree();
        this.areaTree.init(null);

        this.groups = new HashSet<PageArea>();
        this.groupTree = new RTree();
        this.groupTree.init(null);

        this.ungrouped = new ArrayList<PageArea>();

        this.pageHeight = width;
        this.pageWidth = height;

        this.time = new StopWatch(true);

        this.buildHierarchy(areas);
    }

    private void buildHierarchy(ArrayList<PageArea> areas)
    {
        ArrayList<PageArea> pool = new ArrayList<PageArea>();
        ArrayList<PageArea> deleteList = new ArrayList<PageArea>();

        pool.addAll(areas);
        Collections.sort(pool, new AreaSizeComparator());
        for (PageArea area: areas) // this can't be pool, because we will modify it in the loop
        {
            for (PageArea a: deleteList)
            {
                pool.remove(a);
            }
            deleteList.clear();

            for (PageArea a: pool)
            {
                if (area == a) break;
                if (!area.contains(a)) continue;

                area.addChild(a);
                deleteList.add(a);
            }
        }
        this.extractLeafAreas(areas);
    }

    public ArrayList<PageArea> getAreas()
    {
        return this.areas;
    }

    private void extractLeafAreas(ArrayList<PageArea> areas)
    {
        this.areas.clear();
        for (PageArea a: areas)
        {
            if (a.getChildren().size() == 0)
            {
                a.setParent(null);
                a.getChildren().clear();
//                if (a.getTop() < 180)
//                    System.out.println("areas.add(new PageArea(Color.black,"+a.getLeft()+","+a.getTop()+","+a.getRight()+","+a.getBottom()+"));");
                this.areas.add(a);
                this.areaTree.add(a.getRectangle(), this.areas.size()-1);
            }
        }
    }

    public HashSet<PageArea> getGroups() throws Exception
    {
        if (this.groups.isEmpty())
        {
            if (!this.areas.isEmpty())
            {
                this.extractGroups(this.areas);
            }
        }

        return this.groups;
    }

    public ArrayList<PageArea> extractGroups(List<PageArea> areas) throws Exception
    {
        ArrayList<PageAreaRelation> relations;
        ArrayList<PageArea> ret = new ArrayList<PageArea>();
        FileWriter fstream;

        if (DEBUG) fstream = new FileWriter("/home/greengo/out.txt");
        else fstream = new FileWriter("/dev/null");
        this.log = new BufferedWriter(fstream);

        relations = this.getAreaGraph(areas);
        this.locateGroups(relations);

        this.ungrouped.clear();
        for (PageArea group: groups)
        {
            ret.add(group);
        }

        for (PageArea area: this.areas)
        {
            if (area.getParent() == null)
            {
                this.ungrouped.add(area);
            }
        }
        this.log.close();

        return ret;
    }

    private void locateGroups(ArrayList<PageAreaRelation> relations) throws Exception
    {
        PageArea a, b;
        int v1, v2, vsum;
        PageAreaRelation relation;
        PageArea group;
        boolean area_overlap;
        AreaMatch match;
        double threshold;
        double similarity;
        int relCnt = relations.size();
        ArrayList<PageAreaRelation> mtRelations = new ArrayList<PageAreaRelation>();
        boolean mergeTest;

        this.time.toggle();
        while (relations.size() > 0)
        {
            do {
                relation = relations.get(0);
                relations.remove(0);
                a = relation.getA();
                b = relation.getB();
            } while (relations.size() > 0 && (a.getParent() != null || b.getParent() != null));

            if (relations.size() == 0 && a.getParent() == null && b.getParent() == null) break;

            this.log.write(relation.toString()+"\n");

            v1 = this.getAreaCount(a);
            v2 = this.getAreaCount(b);
            vsum = v1 + v2;

            /* DOC: see graph of d depending on V2, there is a logarithmic dependency */
//            threshold = similarityThreshold/(Math.log10(v1+v2)+1);
            threshold = similarityThreshold;
            similarity = a.getSimilarityFromGraph(b, relation.getSimilarity());
            mergeTest = this.mergeTest(relation);
            if (similarity > threshold || !mergeTest)
            {
                if (similarity <= threshold && !mergeTest)
                {
                    this.log.write("Merge attempt failed\n");
                    mtRelations.add(relation);
                }
                else if (similarity >= threshold)
                {
                    this.log.write("Similarity comparison failed: "+similarity+" >= "+threshold+"\n");
                }
                if (relations.size() == 0 && mtRelations.size() < relCnt)
                {
                    relations.addAll(mtRelations);
                    relCnt = relations.size();
                    mtRelations.clear();
                }
                continue;
            }

            group = this.mergeAreas(a, b, relation);
            this.log.write("Group: "+group.getTop()+"-"+group.getLeft()+"("+group.getWidth()+"x"+group.getHeight()+") - ("+v1+", "+v2+")\n");


            do {
                match = new AreaMatch();
                this.areaTree.intersects(group.getRectangle(), match);
                /* It will always overlap with the two areas already in the group */
                area_overlap = (match.getIds().size() > vsum);

                if (area_overlap)
                {
                    this.log.write("overlap = true; vsum = "+vsum+"; matches = "+match.getIds().size()+"\n");
                    /* First try to include all those overlapping areas in the group */
                    if (!this.growGroup(group, match.getIds()))
                    {
                        this.log.write("group grow failed\n");
                        this.reclaim(a);
                        this.reclaim(b);
                        this.returnChildren(group);
                        break;
                    }
                    else
                    {
                        vsum = group.getChildren().size();
                        this.log.write("updated vsum: " + vsum+"\n");
                    }
                }
            } while (area_overlap);

            if (!area_overlap)
            {
                /* Now we have to add children completely */
                this.log.write("Final Group: "+group.getTop()+"-"+group.getLeft()+"("+group.getWidth()+"x"+group.getHeight()+")\n");
                this.transferRelations(a, b, group, relations);
                this.groups.remove(a);
                this.groups.remove(b);
                this.groups.add(group);
                this.groupTree.delete(a.getRectangle(), 0);
                this.groupTree.delete(b.getRectangle(), 0);
                this.groupTree.add(group.getRectangle(), 0);
            }

            if (relations.size() == 0 && mtRelations.size() < relCnt)
            {
                relations.addAll(mtRelations);
                relCnt = relations.size();
                mtRelations.clear();
            }
        }
        this.time.toggle();
        System.out.println("Total lookup time: " + this.time.getTotal()/1000000 + " ms");
    }

    private boolean growGroup(PageArea group, ArrayList<Integer> areas)
    {
        boolean merged = true;
        ArrayList<Integer> copy = new ArrayList<Integer>();
        copy.addAll(areas);
        PageArea area;
        Integer index;

        /* At the first pass, allow growing group only for areas that are
         * actually bordering/overlapping with different areas in the group */

        /* First identify that all the areas are either in the group or are
         * matching the condition above (overlap/borderline with other areas in the group) */
        while (merged)
        {
            merged = false;
            for (int i = 0 ; i < copy.size() ; i++)
            {
                index = copy.get(i);
                area = this.areas.get(index);
                this.log.write("area test for merge: "+area.toString());
                if (area.getParent() == group)
                {
                    copy.remove(i);
                    this.log.write(" (already in the group)\n");
                    i--;
                    continue;
                }
                else if (area.getParent() != null)
                {
                    /* This belongs to another group - that's a show stopper */
                    this.log.write(" (belongs to another group)\n");
                    return false;
                }
                else
                {
                    for (PageArea child: group.getChildren())
                    {
                        if (area.overlaps(child))
                        {
                            merged = true;
                            group.addChild(area);
                            this.log.write(" (merged - overlap)\n");
                            break;
                        }
                    }

                    if (merged == true)
                    {
                        copy.remove(i);
                        i--;
                        break;
                    }
                }
            }
        }

        if (copy.size() > 0)
        {
            /* There are some areas that don't overlap with areas in the group */
            return false;
        }

        return true;
    }

    private void returnChildren(PageArea group)
    {
        for (PageArea child: group.getChildren())
        {
            /* We need to return all areas that have been added to
             * the tmpGroup (not those inherited from the original group)
             * to the pool */
            if (child.getParent() == group)
            {
                child.setParent(null);
            }
        }
    }

    private PageArea mergeAreas(PageArea a, PageArea b, PageAreaRelation rel)
    {
        int e, e1, e2;
        double m, x;
        PageArea group;
        int vert, horiz;

        x = rel.getSimilarity();

        e1 = a.getChildren().size();
        e2 = b.getChildren().size();
        e = a.getEdgeCount()+b.getEdgeCount();
        e += ((e1 > 0)?e1:1)*((e2 > 0)?e2:1);

        m = getMergedM(a, b, x);

        vert = a.getVEdgeCount()+b.getVEdgeCount();
        horiz = a.getHEdgeCount()+b.getHEdgeCount();
        if (rel.getDirection() == PageAreaRelation.DIRECTION_VERTICAL)
        {
            vert += rel.getCardinality();
        }
        else
        {
            horiz += rel.getCardinality();
        }

        group = new PageArea(a);
        group.setEdgeCount(e);
        group.setVEdgeCount(vert);
        group.setHEdgeCount(horiz);
        group.setMeanDistance(m);


        this.mergeChildren(group, a);
        this.mergeChildren(group, b);

        return group;
    }

    private void mergeChildren(PageArea group, PageArea a)
    {
        if (a.getChildren().size() > 0)
        {
            for (PageArea child: a.getChildren())
            {
                group.addChild(child);
            }
        }
        else
        {
            group.addChild(a);
        }
    }

    private void reclaim(PageArea a)
    {
        if (a.getChildren().size() == 0)
        {
            a.setParent(null);
        }
        else
        {
            for (PageArea child: a.getChildren())
            {
                child.setParent(a);
            }
        }
    }

    double getMergedM(PageArea a, PageArea b, double x)
    {
        int v1, v2, e1, e2;
        double m1, m2;

        if (a.getChildren().size() > 0)
        {
            v1 = a.getChildren().size();
        }
        else
        {
            v1 = 0;
        }

        if (b.getChildren().size() > 0)
        {
            v2 = b.getChildren().size();
        }
        else
        {
            v2 = 0;
        }

        e1 = a.getEdgeCount();
        e2 = b.getEdgeCount();
        m1 = a.getMeanDistance();
        m2 = b.getMeanDistance();

        return (e1*m1+(v1-1)*v2*m1 + v1*v2*x + v1*(v2-1)*m2+e2*m2)/(e1+v1*v2+e2);
    }

    private int getAreaCount(PageArea a)
    {
        if (a.getChildren().size() > 0)
        {
            return a.getChildren().size();
        }
        else
        {
            return 1;
        }
    }

    private boolean mergeTest(PageAreaRelation rel)
    {
        PageArea a, b;
        int direction;
        int aShape, bShape;

        a = rel.getA();
        b = rel.getB();
        direction = rel.getDirection();

        aShape = a.getShape();
        bShape = b.getShape();

        if (direction == PageAreaRelation.DIRECTION_HORIZONTAL)
        {
            if (aShape == bShape)
            {
            }
            else
            {
                return this.mergeTestAlignment(a, b);
            }
        }
        else
        {
            if (aShape == bShape)
            {
            }
            else
            {
                return this.mergeTestAlignment(a, b);
            }
        }
    }

    private boolean mergeTestAlignment(PageArea a, PageArea b)
    {
        PageArea tmpArea;
        AreaMatch match;
        int areaCnt;

        tmpArea = new PageArea(a);
        tmpArea.addChild(b, true);
        areaCnt = this.getAreaCount(a)+this.getAreaCount(b);

        match = new AreaMatch();
        this.areaTree.intersects(tmpArea.getRectangle(), match);
        return (match.getIds().size() <= areaCnt);
    }


    private void transferRelations(PageArea oldGroup1, PageArea oldGroup2, PageArea newGroup, List<PageAreaRelation> relations) throws IOException
    {
        int i;
        PageAreaRelation rel;
        PageAreaRelation bestRel;
        PageArea candidate;
        HashMap<PageArea, PageAreaRelation> tmpRelations = new HashMap<PageArea, PageAreaRelation>();

        for (i = 0; i < relations.size(); i++)
        {
            rel = relations.get(i);

            if (oldGroup1 == rel.getA()) candidate = rel.getB();
            else if (oldGroup1 == rel.getB()) candidate = rel.getA();
            else candidate = null;

            if (candidate == null)
            {
                if (oldGroup2 == rel.getA()) candidate = rel.getB();
                else if (oldGroup2 == rel.getB()) candidate = rel.getA();
                else continue;
            }

            if (candidate != null)
            {
                this.log.write("Selected candidate: "+candidate.toString()+"\n");
                /* Just to be sure, test the candidate for both old groups
                 * (code above can change in the future) */
                if (candidate != oldGroup1 && candidate != oldGroup2)
                {
                    /* Again, just in case ... */
                    if (candidate.getParent() != null)
                    {
                        this.log.write("parent is not null\n");
                        if (candidate.getParent() == newGroup)
                        {
                            this.log.write("parent is the new group\n");
                            if (rel.getDirection() == PageAreaRelation.DIRECTION_HORIZONTAL)
                            {
                                newGroup.addHEdgeCount(rel.getCardinality());
                            }
                            else
                            {
                                newGroup.addVEdgeCount(rel.getCardinality());
                            }
                            newGroup.setEdgeCount(rel.getCardinality());
                            candidate = null;
                        }
                        else
                        {
                            candidate = candidate.getParent();
                        }
                    }

                    if (candidate != null)
                    {
                        this.log.write("candidate is not null\n");
                        if (tmpRelations.containsKey(candidate))
                        {
                            bestRel = tmpRelations.get(candidate);
                            bestRel.addCardinality(rel.getCardinality());
                            if (rel.getSimilarity() < bestRel.getSimilarity())
                            {
                                bestRel.setSimilarity(rel.getSimilarity());
                            }
                        }
                        else
                        {
                            bestRel = new PageAreaRelation(newGroup, candidate, rel.getSimilarity(), rel.getDirection());
                            bestRel.setCardinality(rel.getCardinality());
                            tmpRelations.put(candidate, bestRel);
                        }
                        this.log.write("remove "+candidate.toString()+"\n");
                        relations.remove(i); /* Using "i" here instead of "rel" boosts perf. (6s -> 2.5s) */
                        i--; // since we removed the relation, we need to scan the one that took its place
                    }
                }
            }
        }

        for (Map.Entry<PageArea, PageAreaRelation> entry : tmpRelations.entrySet())
        {
            relations.add(entry.getValue());
        }

        Collections.sort(relations, new AreaSimilarityComparator());
    }

    private ArrayList<PageAreaRelation> getAreaGraph(List<PageArea> areas)
    {
        ArrayList<PageAreaRelation> relations = new ArrayList<PageAreaRelation>();
        ArrayList<PageAreaRelation> tmpRelations = new ArrayList<PageAreaRelation>();
        PageArea a;
        Rectangle selector;


        for (int i = 0; i < areas.size(); i++)
        {
            a = areas.get(i);
            /* First go right */
            selector = new Rectangle(a.getLeft(), a.getTop(), this.pageWidth, a.getBottom());
            tmpRelations = this.findRelations(tmpRelations, a, selector, PageAreaRelation.DIRECTION_HORIZONTAL);
            this.processRelations(tmpRelations, relations, true);

            /* Now go down */
            selector = new Rectangle(a.getLeft(), a.getTop(), a.getRight(), this.pageHeight);
            tmpRelations = this.findRelations(tmpRelations, a, selector, PageAreaRelation.DIRECTION_VERTICAL);
            this.processRelations(tmpRelations, relations, true);
        }

        Collections.sort(relations, new AreaSimilarityComparator());

        return relations;
    }

    private ArrayList<PageAreaRelation> findRelations(ArrayList<PageAreaRelation> relations, PageArea area, Rectangle selector, int direction)
    {
        AreaMatch match;
        PageArea b;
        double similarity;
        PageAreaRelation rel;
        ArrayList<PageAreaRelation> tmpRelations = new ArrayList<PageAreaRelation>();

        match = new AreaMatch();
        this.areaTree.intersects(selector, match);
        for (Integer index: match.getIds())
        {
            b = areas.get(index);
            if (area == b) continue;
            similarity = area.getSimilarity(b);
            rel = new PageAreaRelation(area, b, similarity, direction);
            rel.setAbsoluteDistance(area.getDistanceAbsolute(b));
            tmpRelations.add(rel);
        }

        return tmpRelations;
    }

    private void processRelations(ArrayList<PageAreaRelation> batch, ArrayList<PageAreaRelation> all, boolean append)
    {
        if (batch.size() > 0)
        {
            if (append) all.addAll(batch);
            batch.clear();
        }
    }

    public ArrayList<PageArea> getUngrouped()
    {
        return this.ungrouped;
    }
}
