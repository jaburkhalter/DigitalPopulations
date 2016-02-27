package mil.army.usace.ehlschlaeger.digitalpopulations.censusgen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import mil.army.usace.ehlschlaeger.digitalpopulations.ConstrainedRealizer;
import mil.army.usace.ehlschlaeger.digitalpopulations.PumsHousehold;
import mil.army.usace.ehlschlaeger.digitalpopulations.PumsHouseholdRealization;
import mil.army.usace.ehlschlaeger.digitalpopulations.PumsQuery;
import mil.army.usace.ehlschlaeger.digitalpopulations.Realizer;
import mil.army.usace.ehlschlaeger.digitalpopulations.io.HohRznWriter;
import mil.army.usace.ehlschlaeger.rgik.core.GISClass;
import mil.army.usace.ehlschlaeger.rgik.core.GISLattice;
import mil.army.usace.ehlschlaeger.rgik.core.RGIS;
import mil.army.usace.ehlschlaeger.rgik.io.StringOutputStream;
import mil.army.usace.ehlschlaeger.rgik.util.LogUtil;
import mil.army.usace.ehlschlaeger.rgik.util.ObjectUtil;



/**
 * Move households around to find the arrangement that best fits the
 * statistic evaluators.
 * <P>
 * Is phase 3 of censusgen.
 * <p>
 * Copyright <a href="http://faculty.wiu.edu/CR-Ehlschlaeger2/">Charles R.
 * Ehlschlaeger</a>, work: 309-298-1841, fax: 309-298-3003, This software is
 * freely usable for research and educational purposes. Contact C. R.
 * Ehlschlaeger for permission for other purposes. Use of this software requires
 * appropriate citation in all published and unpublished documentation.
 * 
 * @author William R. Zwicky
 */
public class Phase_OptimizeRegions {
    protected static Logger log = Logger.getLogger(Phase_OptimizeRegions.class.getPackage().getName());

    protected Solution soln;
    protected GISClass regionMap;
    protected ArrayList<Integer> regionList;
    protected GISLattice popDensityMap;
    
    /** Our run-time configuration. */
    protected Params params = new Params();
    protected Random random = new Random();

    protected transient Realizer realizer = null;

    /**
     * Build standard instance. You will generally want to call setParams() and
     * setRandomSource(), then call go() to run and generate output files, or
     * call writeFileSet() to save given solution to disk.
     * 
     * @param solution
     *            initial arrangement that we will optimize
     * @param regionMap
     *            map which specifies the region that covers each cell
     * @param popDensityMap
     *            raster map of relative population density for each cell. Only
     *            used to guide generation of random locations for houses that
     *            are written to disk.
     */
    public Phase_OptimizeRegions(Solution solution,
            GISClass regionMap,
            GISLattice popDensityMap) {
        this.soln = solution;
        this.regionMap = regionMap;
        this.popDensityMap = popDensityMap;
        
        this.regionList = new ArrayList<Integer>(regionMap.makeInventory());
        Collections.sort(regionList);
    }

    /**
     * Install run-time configuration.
     * 
     * @param params current set of run-time parameters.  MUST include phase 3 params.
     */
    public void setParams(Params params) {
        this.params = params;
    }
    
    /**
     * Change our source of random numbers.
     * 
     * @param source
     *            new random number generator
     */
    public void setRandomSource(Random source) {
        random = source;
    }
    
    /**
     * Install a custom realizer. In this class, this is only used to generate
     * easting/northing values when writing output files. If none is provided
     * here, we will generate one from our inputs.
     * 
     * @param rzr
     *            new realizer
     */
    public void setRealizer(Realizer rzr) {
        this.realizer = rzr;
    }
    
    /**
     * Perform the process as currently configured.
     * 
     * @throws IOException on any error writing output files
     */
    public void go(int realizationNum) {
        assert soln.householdArchTypes != null && soln.householdArchTypes.length > 0;
        
        double bestFit = soln.getSpread();
        
        // If pstats is null, attMapHelper will just return regionList.
        AttMapHelper attMapHelper = new AttMapHelper(
            regionMap, regionList,
            soln.pcons);
        
        // Timers (1 billion nanoseconds per second)
        long tMainStart = System.nanoTime();
        long tNextStats = tMainStart + 60 * (long)1e9;
        long tNextSave = tMainStart + (long)(params.getPhase3SaveIntermediate() * 60 * 1e9);

        // Determine time limit.
        long tMainAbort = -1;
        if(params.getPhase3TimeLimit() > 0)
            tMainAbort = tMainStart + (long)(params.getPhase3TimeLimit() * 60 * 1e9);

        // Print field reference for the lines that will follow
        LogUtil.cr(log);
        printHeader();
        printStats(0, 0, tMainStart);

        // Init loop vars.
        int hoh = random.nextInt(soln.householdArchTypes.length);
        int startHoh = hoh;
        long moves = 0;
        long fails = 0;
        long movesAtLastSave = 0;
        Object[] diagnostic = new Object[1];
        
        // Move households around in search of a better fit.
        // Continue moving until we can find no more moves.
        hoh_loop: for(;;) {
            boolean houseMoved = false;
            PumsHousehold house = soln.householdArchTypes[hoh];
            // Track tractable tracts.
            BitSet tested = new BitSet();
            
            // Start at a random rzn.
            int startRzn = -1;
            // Move only if we have rzns to move.  (Also, nextInt() crashes on zero.)
            if(house.getNumberRealizations() > 0) {
                startRzn = random.nextInt(house.getNumberRealizations());

                // like phase 2, check attrib maps for validRegions, only try to move to those
                List<Integer> goodRegions = attMapHelper.validRegions(house, diagnostic);

                if (goodRegions.isEmpty() || diagnostic[0] != null) {
                    String msg = String.format(
                        "%s eliminated all regions from consideration for %s.",
                        diagnostic[0], house);
                    log.warning(msg);
                    attMapHelper.diagnose(house, log);

                    // Paranoid check: validRegions() must at least
                    // return the list of all regions; if we get an empty
                    // list, something is horribly wrong.
                    if(goodRegions.isEmpty())
                        throw new IllegalStateException("goodRegions==null due to internal logic bug.");
                }
                
                // Loop from startRzn back to startRzn, wrapping around end.
                for(int i=0; i<house.getNumberRealizations(); i++) {
                    int rzn = (startRzn+i) % house.getNumberRealizations();
                    
                    int origTract = house.getRealizationTract(rzn);
    
                    // Skip tract if we've already tried this archtype here. If
                    // we've failed before, we will fail every other time we
                    // try.
                    //
                    // Note this trick is only valid if we stop scanning rzns after
                    // finding one move. It's possible a rzn at the beginning of the
                    // list will not move, but a later rzn in the same tract *will*
                    // move due to changes in the rzns between them.
                    if(tested.get(origTract))
                        continue;
                    tested.set(origTract);
                    
                    // Best tract (we know of) is current tract; bestFit is already set.
                    int bestTract = house.getRealizationTract(rzn);
                    
                    // Test relocation to every other tract.
                    for (int newTract : goodRegions) {
                        // Skip starting tract.
                        if (newTract == origTract)
                            continue;
    
                        // Re-evaluate all the statistics as if the household
                        // had been moved. We never undo this move in the loop;
                        // move() will cancel the value at the old location for us.
                        // So any sequence of move()s followed by a move(oldTract)
                        // should result in the original set of statistics.
                        soln.move(hoh, rzn, newTract);
                        
                        double newSpread = soln.getSpread();
    
                        // Is new location a better fit?
                        if (newSpread < bestFit) {
                            // Yes! Keep it.
                            bestFit = newSpread;
                            bestTract = newTract;
                            moves++;
                        } else {
                            // No!  Count failure; we'll undo the move below.
                            fails++;
                        }
                    }
                
                    // Move rzn to best tract found. If no better tract was found,
                    // bestTract is simply origTract, and we're moving the rzn back
                    // into place.
                    soln.move(hoh, rzn, bestTract);
                    if(bestTract != origTract) {
                        // We don't want to over-work this archtype, so we'll stop with
                        // the first rzn moved.
                        houseMoved = true;
                        break;
                    }
                    
                    // Iterate next rzn, wrapping around the end.
                    rzn = (rzn+1) % house.getNumberRealizations();
                    // Quit when we hit our starting point.
                    if(rzn == startRzn)
                        break;
                } // for(rzn)
            }
            
            // -- Do time-sensitive tasks -- //
            long tNow = System.nanoTime();
            
            // Abort run after time limit if requested.
            if(tMainAbort > 0 && tNow > tMainAbort) {
                printStats(moves, fails, tMainStart);
                LogUtil.progress(log, "** Aborting run: time limit has been reached.");
                break hoh_loop;
            }
            
            // Print stats every minute.
            if(tNow > tNextStats) {
                // Perform dump at uniform increments.
                tNextStats += 60 * (long)1e9;
                // Dump stats
                printStats(moves, fails, tMainStart);
            }
            
            // Save intermediate results periodically.
            if(tNow > tNextSave) {
                // Save only if something has changed.
                if(moves != movesAtLastSave) {
                    movesAtLastSave = moves;

                    LogUtil.progress(log, "Long run, saving intermediate data set.");
                    try {
                        writeFileSet(realizationNum, "intermediate", null);
                    } catch (IOException e) {
                        log.log(Level.WARNING, "Unable to save intermediate data, continuing anyway.", e);
                    }
                }
                // Save again precisely one increment from now. Ignore
                // however late we are performing this save, and also
                // ignore however long this save took.
                tNextSave = System.nanoTime() + (long)(params.getPhase3SaveIntermediate() * 60 * 1e9);
            }

            
            // Pick another archtype.
            if(houseMoved) {
                // As long as we find moves, we'll just bounce around randomly.
                hoh = random.nextInt(soln.householdArchTypes.length);
                startHoh = hoh;
            }
            else {
                // Failed to find a move for this archtype, so proceed to next
                // one in order, wrapping around the end.
                hoh = (hoh+1) % soln.householdArchTypes.length;
                // Quit when we hit our starting point.
                if(hoh == startHoh) {
                    // We've probed every rzn of every hoh and could find no
                    // moves. Time to give up!
                    printStats(moves, fails, tMainStart);
                    LogUtil.progress(log, "** Aborting run: All households tested, no more moves found.");
                    break hoh_loop;
                }
            }
        } // hoh_loop
    }

    /**
     * Print field reference for the lines that will follow.
     * Must be synchronized with _p3_printStats.
     */
    protected void printHeader() {
        StringOutputStream sos = new StringOutputStream();
        sos.format("%8s %11s %6s", "Moves", "Fails", "Mins");
        for( int i = 0; i < soln.stats.size(); i++) {
            sos.format( " %10s", "stat[" + i + "]");
        }
        sos.format( " %s", "Spread");
        LogUtil.progress(log, sos.toString());
    }

    /**
     * Print stats for the progress achieved so far.
     * Must be synchronized with _p3_printHeader.
     * 
     * @param moves
     * @param fails
     * @param startTime
     */
    protected void printStats(long moves, long fails, long startTime) {
        long tNow = System.nanoTime();
        double totalSeconds = (tNow-startTime) / (long)1e9;
        
        StringOutputStream sos = new StringOutputStream();
        sos.format("%8d %11d %6.1f", moves, fails, totalSeconds/60.0);
        for (int iii = 0; iii < soln.stats.size(); iii++) {
            sos.format(" %10.1f",
                Math.sqrt(soln.stats.get(iii).spread(soln.goals.get(iii))));
        }
        double bestFit = soln.getSpread();
        sos.format(" %f", Math.sqrt(bestFit));
        LogUtil.progress(log, sos.toString());
    }

    /**
     * Write all required output files, and remove older versions.
     * 
     * @param runNumber
     *            realization ID of current solution
     * @param nameNote
     *            tag to append to file name
     * @param pumsQuery
     *            subset of households to write
     * @param allHohFields
     *            true to write all household data fields to output file, false
     *            to only write easting/northing/household id/realization id.
     * @param allPopFields
     *            true to write all population data fields to output file, false
     *            to only write easting/northing/household id/realization id.
     * 
     * @throws IOException
     */
    public void writeFileSet(int runNumber, String nameNote,
            PumsQuery pumsQuery) throws IOException {
        // Realize archtypes and assign locations.
        if(realizer == null) {
            // Preserve realizer for every call to writeFileSet.
            realizer = new ConstrainedRealizer(regionMap, popDensityMap, soln.pcons);
            realizer.setRandomSource(random);
        }

        // I built that nifty iterator system so I wouldn't have to do this ..
        // but it turns out the best way to preserve the generated easting and
        // northing values is to generate and capture the whole list.
        // If we have enough memory for phase 4, we have enough memory for this.
        ArrayList<PumsHouseholdRealization> hohs = ObjectUtil.list(realizer.iterate(
            Arrays.asList(soln.householdArchTypes).iterator()));
        
        HohRznWriter writer = new HohRznWriter(RGIS.getOutputFolder());

        // Write filtered set first.
        if(pumsQuery != null) {
            String newNote = "(filtered)" + ObjectUtil.nz(nameNote);

            writer.writeFileSet(
                runNumber, newNote,
                pumsQuery.iterateRzn(hohs.iterator()),
                true,
                false,
                params.getWriteAllHohFields(), params.getWriteAllPopFields(),
                soln.hohKeyCol, soln.popHohCol);
        }
        
        // Now write complete set.
        writer.writeFileSet(
            runNumber, nameNote,
            hohs.iterator(),
            true,
            true,
            params.getWriteAllHohFields(), params.getWriteAllPopFields(),
            soln.hohKeyCol, soln.popHohCol);
    }
}
