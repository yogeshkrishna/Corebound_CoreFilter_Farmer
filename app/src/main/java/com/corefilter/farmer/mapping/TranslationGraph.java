package com.corefilter.farmer.mapping;

import java.util.*;

/** Translation constraints only. No scale, rotation, invented joins or pixel warping. */
public final class TranslationGraph {
    public static final class Edge {
        public final int a,b; public final double dx,dy,weight;
        public Edge(int a,int b,double dx,double dy,double weight){this.a=a;this.b=b;this.dx=dx;this.dy=dy;this.weight=weight;}
    }
    public static final class Pose {
        public double x,y; public int section=-1;
    }
    public static Pose[] solve(int count,boolean[] usable,List<Edge> observations) {
        Pose[] poses=new Pose[count];for(int i=0;i<count;i++)poses[i]=new Pose();
        List<Edge> sorted=new ArrayList<>(observations);sorted.sort((a,b)->Double.compare(b.weight,a.weight));
        int[] parent=new int[count];for(int i=0;i<count;i++)parent[i]=i;
        List<List<Edge>> tree=new ArrayList<>(),links=new ArrayList<>();for(int i=0;i<count;i++){tree.add(new ArrayList<>());links.add(new ArrayList<>());}
        Set<Edge> treeEdges=new HashSet<>();
        for(Edge e:sorted){if(e.a<0||e.b<0||e.a>=count||e.b>=count||!usable[e.a]||!usable[e.b])continue;
            int a=find(parent,e.a),b=find(parent,e.b);if(a!=b){parent[b]=a;tree.get(e.a).add(e);tree.get(e.b).add(e);treeEdges.add(e);}}
        int sections=0;boolean[] anchor=new boolean[count];
        for(int i=0;i<count;i++)if(usable[i]&&poses[i].section<0){int section=sections++;anchor[i]=true;poses[i].section=section;
            ArrayDeque<Integer> q=new ArrayDeque<>();q.add(i);while(!q.isEmpty()){int a=q.remove();for(Edge e:tree.get(a)){int b=e.a==a?e.b:e.a;
                if(poses[b].section>=0)continue;double sign=e.a==a?1:-1;poses[b].x=poses[a].x+sign*e.dx;poses[b].y=poses[a].y+sign*e.dy;poses[b].section=section;q.add(b);}}}
        // Repetitive scenery can produce a strong but contradictory non-tree match.
        // Exclude it before refinement, rather than bending the entire cave to fit it.
        for(Edge e:sorted)if(e.a>=0&&e.b>=0&&e.a<count&&e.b<count&&poses[e.a].section>=0&&poses[e.a].section==poses[e.b].section){
            double error=Math.hypot(poses[e.b].x-poses[e.a].x-e.dx,poses[e.b].y-poses[e.a].y-e.dy);
            if(treeEdges.contains(e)||error<=24){links.get(e.a).add(e);links.get(e.b).add(e);}}
        for(int pass=0;pass<400;pass++){double movement=0;
            for(int k=0;k<count;k++){int i=pass%2==0?k:count-1-k;if(anchor[i]||poses[i].section<0||links.get(i).isEmpty())continue;
                double x=0,y=0,w=0;for(Edge e:links.get(i)){int j=e.a==i?e.b:e.a;double sign=e.a==i?-1:1;
                    double tx=poses[j].x+sign*e.dx,ty=poses[j].y+sign*e.dy;
                    double residual=Math.hypot(tx-poses[i].x,ty-poses[i].y);double weight=e.weight*Math.min(1,4/Math.max(4,residual));
                    x+=tx*weight;y+=ty*weight;w+=weight;}
                double nx=x/w,ny=y/w;movement=Math.max(movement,Math.hypot(nx-poses[i].x,ny-poses[i].y));poses[i].x=nx;poses[i].y=ny;}
            if(movement<.005)break;}
        return poses;
    }
    private static int find(int[] p,int a){while(p[a]!=a){p[a]=p[p[a]];a=p[a];}return a;}
}
