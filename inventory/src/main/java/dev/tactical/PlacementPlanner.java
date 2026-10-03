package dev.tactical;

import java.util.*;

/** Plans a complete replacement without changing either inventory. */
public final class PlacementPlanner {
    private static final int SEARCH_LIMIT = 250_000;

    public record Region(int id, int width, int height, boolean rig, boolean fixedSlot, int itemLimit) {
        public Region(int id,int width,int height,boolean rig,boolean fixedSlot) {
            this(id,width,height,rig,fixedSlot,fixedSlot?1:Integer.MAX_VALUE);
        }
    }
    public record Item(int id, int region, Grid.Rect rectangle, int width, int height,
                       boolean rotated, boolean movable, Set<Integer> allowedRegions) {
        public Item { allowedRegions = Set.copyOf(allowedRegions); }
    }
    public record Model(List<Region> regions, List<Item> items) {
        public Model { regions = List.copyOf(regions); items = List.copyOf(items); }
    }
    public record Placement(int item, int region, int x, int y, boolean rotated) {}
    public record Plan(List<Placement> placements) {
        public Plan { placements = List.copyOf(placements); }
    }
    private record Candidate(Placement placement, Grid.Rect rectangle) {}
    private record Occupied(int region, Grid.Rect rectangle) {}

    private PlacementPlanner() {}

    /** The incoming root is fixed. Only items intersecting its footprint may move. */
    public static Plan plan(Model model, int source, int destinationRegion, int x, int y,
                            boolean rotated, boolean entire) {
        var regions = new LinkedHashMap<Integer, Region>();
        for (var region : model.regions()) {
            if (region.width() < 1 || region.height() < 1 || region.width() > 128 || region.height() > 4096
                    || region.itemLimit()<1 || regions.put(region.id(), region) != null) return null;
        }
        var items = new LinkedHashMap<Integer, Item>();
        for (var item : model.items()) {
            if (item.width() < 1 || item.height() < 1 || items.put(item.id(), item) != null) return null;
        }
        var incoming = items.get(source);
        var destination = regions.get(destinationRegion);
        if (incoming == null || !incoming.movable() || destination == null
                || !incoming.allowedRegions().contains(destinationRegion)) return null;
        var placement = new Placement(source, destinationRegion, x, y, rotated);
        var rectangle = rectangle(incoming, destination, placement);
        if (!within(destination, rectangle)) return null;

        var displaced = new ArrayList<Item>();
        for (var item : model.items()) {
            if (item.id() == source && entire) continue;
            if (item.region() == destinationRegion && rectangle.overlaps(item.rectangle())) {
                if (!entire || item.id() == source || !item.movable()) return null;
                displaced.add(item);
            }
        }
        var occupied = new ArrayList<Occupied>();
        var moving = new HashSet<Integer>();
        for (var item : displaced) moving.add(item.id());
        for (var item : model.items()) {
            if (moving.contains(item.id()) || item.id() == source && entire) continue;
            occupied.add(new Occupied(item.region(), item.rectangle()));
        }
        occupied.add(new Occupied(destinationRegion, rectangle));
        for(var region:regions.values()) if(occupied.stream().filter(other->other.region()==region.id()).count()>region.itemLimit()) return null;
        if (displaced.isEmpty()) return new Plan(List.of(placement));

        // A replacement can use the source region and the destination region, never unrelated storage.
        var candidateRegions = new ArrayList<Region>();
        var sourceRegion = regions.get(incoming.region());
        if (sourceRegion != null && sourceRegion.id() != 2) candidateRegions.add(sourceRegion);
        if (destinationRegion != incoming.region() && destinationRegion != 2) candidateRegions.add(destination);
        if (candidateRegions.isEmpty()) return null;
        var search = new Search(incoming, placement, displaced, candidateRegions, occupied);
        if (!search.solve()) return null;
        var result = new ArrayList<Placement>();
        result.add(placement);
        // Preserve stable item order in the public result, independent of search branching order.
        for (var item : displaced) result.add(search.answer.get(item.id()));
        return new Plan(result);
    }

    private static Grid.Rect rectangle(Item item, Region region, Placement placement) {
        return new Grid.Rect(placement.x(), placement.y(), region.fixedSlot() ? 1 : placement.rotated() ? item.height() : item.width(),
                region.fixedSlot() ? 1 : placement.rotated() ? item.width() : item.height());
    }

    private static boolean within(Region region, Grid.Rect rectangle) {
        if (region.fixedSlot()) return rectangle.equals(new Grid.Rect(0, 0, 1, 1));
        return Grid.fits(region.rig() ? 0 : 1, region.width(), region.height(), rectangle, List.of());
    }

    private static final class Search {
        private final Item source;
        private final Placement incoming;
        private final List<Item> remaining;
        private final List<Region> regions;
        private final List<Occupied> occupied;
        private final Map<Integer, List<Candidate>> candidates = new HashMap<>();
        private final Map<Integer, Placement> answer = new HashMap<>();
        private final Set<String> failed = new HashSet<>();
        private int visited;

        private Search(Item source, Placement incoming, List<Item> displaced, List<Region> regions, List<Occupied> occupied) {
            this.source = source;
            this.incoming = incoming;
            this.remaining = new ArrayList<>(displaced);
            this.regions = regions;
            this.occupied = occupied;
            for (var item : displaced) candidates.put(item.id(), candidates(item));
        }

        private List<Candidate> candidates(Item item) {
            var result = new ArrayList<Candidate>();
            for (var region : regions) {
                if (!item.allowedRegions().contains(region.id())) continue;
                for (int direction = 0; direction < (region.fixedSlot() || item.width() == item.height() ? 1 : 2); direction++) {
                    boolean rotation = direction == 0 ? item.rotated() : !item.rotated();
                    int width = region.fixedSlot() ? 1 : rotation ? item.height() : item.width();
                    int height = region.fixedSlot() ? 1 : rotation ? item.width() : item.height();
                    for (int y = 0; y <= region.height() - height; y++) for (int x = 0; x <= region.width() - width; x++) {
                        var placement = new Placement(item.id(), region.id(), x, y, rotation);
                        var rectangle = rectangle(item, region, placement);
                        if (within(region, rectangle) && free(region.id(), rectangle)) result.add(new Candidate(placement, rectangle));
                    }
                }
            }
            result.sort(Comparator.comparingInt((Candidate candidate) -> candidate.placement().region() == source.region() ? 0 : 1)
                    .thenComparingLong(candidate -> preferredDistance(item,candidate.placement()))
                    .thenComparingInt(candidate -> distance(candidate.placement(), item.rectangle()))
                    .thenComparingInt(candidate -> candidate.placement().rotated() == item.rotated() ? 0 : 1)
                    .thenComparingInt(candidate -> candidate.placement().y())
                    .thenComparingInt(candidate -> candidate.placement().x()));
            return result;
        }

        private static int distance(Placement placement, Grid.Rect origin) {
            long distance = Math.abs((long) placement.x() - origin.x()) + Math.abs((long) placement.y() - origin.y());
            return (int) Math.min(Integer.MAX_VALUE, distance);
        }

        /** Preserve the covered layout translated into the space freed by the incoming item. */
        private long preferredDistance(Item item,Placement placement) {
            long preferredX=item.rectangle().x(),preferredY=item.rectangle().y();
            if(placement.region()==source.region()) {
                preferredX+=(long)source.rectangle().x()-incoming.x();
                preferredY+=(long)source.rectangle().y()-incoming.y();
            }
            return Math.abs(placement.x()-preferredX)+Math.abs(placement.y()-preferredY);
        }
        private boolean exactTranslated(Item item,List<Candidate> legal) {
            return legal.stream().anyMatch(candidate->candidate.placement().region()==source.region()
                    && preferredDistance(item,candidate.placement())==0);
        }

        private boolean free(int region, Grid.Rect rectangle) {
            var storage=regions.stream().filter(candidate->candidate.id()==region).findFirst().orElse(null);
            return storage!=null && occupied.stream().filter(other->other.region()==region).count()<storage.itemLimit()
                    && occupied.stream().noneMatch(other -> other.region() == region && rectangle.overlaps(other.rectangle()));
        }

        private boolean enoughArea() {
            long totalNeeded = 0, totalFree = 0, totalSlots=0;
            for (var region : regions) {
                long free = (long) region.width() * region.height();
                for (var other : occupied) if (other.region() == region.id()) {
                    var rectangle = other.rectangle();
                    long width = Math.max(0, Math.min((long) region.width(), (long) rectangle.x() + rectangle.w()) - Math.max(0, rectangle.x()));
                    long height = Math.max(0, Math.min((long) region.height(), (long) rectangle.y() + rectangle.h()) - Math.max(0, rectangle.y()));
                    free -= width * height;
                }
                totalFree += Math.max(0, free);
                totalSlots += Math.max(0,(long)region.itemLimit()-occupied.stream().filter(other->other.region()==region.id()).count());
            }
            for (var item : remaining) {
                boolean fixed = regions.stream().anyMatch(region -> region.fixedSlot() && item.allowedRegions().contains(region.id()));
                totalNeeded += fixed ? 1 : (long) item.width() * item.height();
            }
            return totalNeeded <= totalFree && remaining.size()<=totalSlots;
        }

        private boolean solve() {
            if (remaining.isEmpty()) return true;
            if (++visited > SEARCH_LIMIT || !enoughArea()) return false;
            String key = key();
            if (failed.contains(key)) return false;
            Item next = null;
            List<Candidate> choices = null;
            boolean exact=false;
            for (var item : remaining) {
                var legal = candidates.get(item.id()).stream().filter(candidate -> free(candidate.placement().region(), candidate.rectangle())).toList();
                if (legal.isEmpty()) { failed.add(key); return false; }
                boolean translated=exactTranslated(item,legal);
                if (choices == null || translated && !exact || translated==exact && (legal.size() < choices.size()
                        || legal.size() == choices.size() && item.width() * item.height() > next.width() * next.height())) {
                    next = item; choices = legal; exact=translated;
                }
            }
            int index = remaining.indexOf(next);
            remaining.remove(index);
            for (var candidate : choices) {
                occupied.add(new Occupied(candidate.placement().region(), candidate.rectangle()));
                answer.put(next.id(), candidate.placement());
                if (solve()) return true;
                answer.remove(next.id()); occupied.remove(occupied.size() - 1);
                if (visited > SEARCH_LIMIT) break;
            }
            remaining.add(index, next);
            failed.add(key);
            return false;
        }

        private String key() {
            var result = new StringBuilder();
            remaining.stream().map(Item::id).sorted().forEach(id -> result.append(id).append(','));
            result.append('|');
            occupied.stream().sorted(Comparator.comparingInt(Occupied::region).thenComparingInt(other -> other.rectangle().y())
                            .thenComparingInt(other -> other.rectangle().x()).thenComparingInt(other -> other.rectangle().w()).thenComparingInt(other -> other.rectangle().h()))
                    .forEach(other -> result.append(other.region()).append(':').append(other.rectangle().x()).append(',')
                            .append(other.rectangle().y()).append(',').append(other.rectangle().w()).append(',').append(other.rectangle().h()).append(';'));
            return result.toString();
        }
    }
}
