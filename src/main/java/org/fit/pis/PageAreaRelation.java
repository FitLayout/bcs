package org.fit.pis;

public class PageAreaRelation
{
    private PageArea a;
    private PageArea b;
    private double similarity;

    private int direction;

    public static final int DIRECTION_VERTICAL = 0;
    public static final int DIRECTION_HORIZONTAL = 1;

    private int cardinality;

    public PageAreaRelation(PageArea a, PageArea b, double similarity, int direction)
    {
        this.a = a;
        this.b = b;
        this.similarity = similarity;
        this.setDirection(direction);
        this.setCardinality(1);
    }

    public PageArea getA()
    {
        return a;
    }

    public void setA(PageArea a)
    {
        this.a = a;
    }

    public PageArea getB()
    {
        return b;
    }

    public void setB(PageArea b)
    {
        this.b = b;
    }

    public double getSimilarity()
    {
        return similarity;
    }

    public void setSimilarity(double similarity)
    {
        this.similarity = similarity;
    }

    public int getDirection()
    {
        return direction;
    }

    public void setDirection(int direction)
    {
        this.direction = direction;
    }

    public int getCardinality()
    {
        return cardinality;
    }

    public void setCardinality(int cardinality)
    {
        this.cardinality = cardinality;
    }

    public void addCardinality(int cardinality)
    {
        this.cardinality += cardinality;
    }
}
