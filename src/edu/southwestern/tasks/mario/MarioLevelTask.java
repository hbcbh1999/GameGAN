package edu.southwestern.tasks.mario;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ch.idsia.ai.agents.Agent;
import ch.idsia.mario.engine.level.Level;
import ch.idsia.tools.CmdLineOptions;
import ch.idsia.tools.EvaluationInfo;
import ch.idsia.tools.EvaluationOptions;
import edu.southwestern.MMNEAT.MMNEAT;
import edu.southwestern.evolution.GenerationalEA;
import edu.southwestern.evolution.genotypes.Genotype;
import edu.southwestern.parameters.CommonConstants;
import edu.southwestern.parameters.Parameters;
import edu.southwestern.tasks.NoisyLonerTask;
import edu.southwestern.tasks.mario.level.LevelParser;
import edu.southwestern.tasks.mario.level.MarioLevelUtil;
import edu.southwestern.tasks.mario.level.OldLevelParser;
import edu.southwestern.util.ClassCreation;
import edu.southwestern.util.datastructures.ArrayUtil;
import edu.southwestern.util.datastructures.Pair;
import edu.southwestern.util.file.FileUtilities;
import edu.southwestern.util.graphics.GraphicsUtil;
import edu.southwestern.util.random.RandomNumbers;
import org.apache.commons.lang.ArrayUtils;

/**
 * 
 * Evolve Mario levels using an agent,
 * like the Mario A* Agent, as a means of evaluating.
 * Levels can be generated by CPPNs or a GAN, but this is
 * done in child classes.
 * 
 * @author Jacob Schrum
 *
 * @param <T>
 */
public abstract class MarioLevelTask<T> extends NoisyLonerTask<T> {	
	private Agent agent;
	private int numFitnessFunctions;
	private boolean fitnessRequiresSimulation;
	private ArrayList<List<Integer>> targetLevel = null;
	
	public static final int DECORATION_FREQUENCY_STAT_INDEX = 0;
	public static final int LENIENCY_STAT_INDEX = 1;
	public static final int NEGATIVE_SPACE_STAT_INDEX = 2;
	public static final int NUM_SEGMENT_STATS = 3;
	
	public MarioLevelTask() {
		// Replace this with a command line parameter
		try {
			agent = (Agent) ClassCreation.createObject("marioLevelAgent");
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
			System.out.println("Could not instantiate Mario agent");
			System.exit(1);
		}
		
		// Fitness
		numFitnessFunctions = 0;
		fitnessRequiresSimulation = false; // Until proven otherwise
		if(Parameters.parameters.booleanParameter("marioProgressPlusJumpsFitness")) {
			// First maximize progress through the level.
			// If the level is cleared, then maximize the duration of the
			// level, which will indicate that it is challenging.
			MMNEAT.registerFitnessFunction("ProgressPlusJumps");
			fitnessRequiresSimulation = true;
			numFitnessFunctions++;
		} 
		if(Parameters.parameters.booleanParameter("marioProgressPlusTimeFitness")) {
			// Levels that take longer must be harder
			MMNEAT.registerFitnessFunction("ProgressPlusTime");
			fitnessRequiresSimulation = true;
			numFitnessFunctions++;
		}
		if(Parameters.parameters.booleanParameter("marioLevelMatchFitness")) {
			MMNEAT.registerFitnessFunction("LevelMatch");
			numFitnessFunctions++;
			// Load level representation from file here
			String levelFileName = Parameters.parameters.stringParameter("marioTargetLevel"); // Does not have a default value yet
			targetLevel = MarioLevelUtil.listLevelFromVGLCFile(levelFileName);
			
			// View whole dungeon layout
			Level level = Parameters.parameters.booleanParameter("marioGANUsesOriginalEncoding") ? OldLevelParser.createLevelJson(targetLevel) : LevelParser.createLevelJson(targetLevel);			
			BufferedImage image = MarioLevelUtil.getLevelImage(level);
			String saveDir = FileUtilities.getSaveDirectory();
			GraphicsUtil.saveImage(image, saveDir + File.separator + "Target.png");

		}
		// Encourages an alternating periodic pattern of Vanessa's objectives
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingLeniency")) {
			MMNEAT.registerFitnessFunction("AlternatingLeniency");
			numFitnessFunctions++;
		}
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingNegativeSpace")) {
			MMNEAT.registerFitnessFunction("AlternatingNegativeSpace");
			numFitnessFunctions++;			
		}
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingDecoration")) {
			MMNEAT.registerFitnessFunction("AlternatingDecorationFrequency");
			numFitnessFunctions++;
		}

		// Encourages a symmetric pattern of Vanessa's objectives
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricLeniency")) {
			MMNEAT.registerFitnessFunction("SymmetricLeniency");
			numFitnessFunctions++;			
		}
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricNegativeSpace")) {
			MMNEAT.registerFitnessFunction("SymmetricNegativeSpace");
			numFitnessFunctions++;						
		}
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricDecoration")) {
			MMNEAT.registerFitnessFunction("SymmetricDecorationFrequency");
			numFitnessFunctions++;			
		}
		
		if(Parameters.parameters.booleanParameter("marioRandomFitness")) {
			MMNEAT.registerFitnessFunction("Random");
			numFitnessFunctions++;
		}
		
		if(numFitnessFunctions == 0) throw new IllegalStateException("At least one fitness function required to evolve Mario levels");
        // Other scores
        MMNEAT.registerFitnessFunction("Distance", false);
        MMNEAT.registerFitnessFunction("PercentDistance", false);
        MMNEAT.registerFitnessFunction("Time", false);
        MMNEAT.registerFitnessFunction("Jumps", false);
        for(int i=0; i<Parameters.parameters.integerParameter("marioGANLevelChunks"); i++){
            MMNEAT.registerFitnessFunction("DecorationFrequency-"+i,false);
            MMNEAT.registerFitnessFunction("Leniency-"+i,false);
            MMNEAT.registerFitnessFunction("NegativeSpace-"+i,false);
        }

	}
	
	@Override
	public int numObjectives() {
		return numFitnessFunctions;  
	}
	
	public int numOtherScores() {
		return 4 + Parameters.parameters.integerParameter("marioGANLevelChunks") * 3; // Distance, Percentage, Time, and Jumps 
                //plus (decorationFrequency, leniency, negativeSpace) per level segment
	}

	@Override
	public double getTimeStamp() {
		return 0; // Not used
	}

	/**
	 * Different level generators use the genotype to generate a level in different ways
	 * @param individual Genotype 
	 * @return List of lists of integers corresponding to tile types
	 */
	public abstract ArrayList<List<Integer>> getMarioLevelListRepresentationFromGenotype(Genotype<T> individual);
	
	/**
	 * Different level generators generate levels of different lengths
	 * @param info 
	 * @return
	 */
	public abstract double totalPassableDistance(EvaluationInfo info);
	
	@Override
	public Pair<double[], double[]> oneEval(Genotype<T> individual, int num) {
		EvaluationInfo info = null;
		ArrayList<List<Integer>> oneLevel = getMarioLevelListRepresentationFromGenotype(individual);
		if(fitnessRequiresSimulation || CommonConstants.watch) {
			Level level = Parameters.parameters.booleanParameter("marioGANUsesOriginalEncoding") ? OldLevelParser.createLevelJson(oneLevel) : LevelParser.createLevelJson(oneLevel);			
			agent.reset(); // Get ready to play a new level
			EvaluationOptions options = new CmdLineOptions(new String[]{});
			options.setAgent(agent);
			options.setLevel(level);
			options.setMaxFPS(!(agent instanceof ch.idsia.ai.agents.human.HumanKeyboardAgent)); // Run fast when not playing
			options.setVisualization(CommonConstants.watch);
			
			if(CommonConstants.watch) {
				// View whole dungeon layout
				BufferedImage image = MarioLevelUtil.getLevelImage(level);
				String saveDir = FileUtilities.getSaveDirectory();
				int currentGen = ((GenerationalEA) MMNEAT.ea).currentGeneration();
				GraphicsUtil.saveImage(image, saveDir + File.separator + (currentGen == 0 ? "initial" : "gen"+ currentGen) + File.separator + "MarioLevel"+individual.getId()+".png");
			}
			
			List<EvaluationInfo> infos = MarioLevelUtil.agentPlaysLevel(options);
			// For now, assume a single evaluation
			info = infos.get(0);
		}
		double distancePassed = info == null ? 0 : info.lengthOfLevelPassedPhys;
		double percentLevelPassed = info == null ? 0 : distancePassed / totalPassableDistance(info);
		double time = info == null ? 0 : info.timeSpentOnLevel;
		double jumps = info == null ? 0 : info.jumpActionsPerformed;
                
		double[] otherScores = new double[] {distancePassed, percentLevelPassed, time, jumps};
		// Adds Vanessa's Mario stats: Decoration Frequency, Leniency, Negative Space
		ArrayList<double[]> levelStats = LevelParser.getLevelStats(oneLevel, 28);
		for(double[] stats:levelStats){
			otherScores = ArrayUtils.addAll(otherScores, stats);
		}

		ArrayList<Double> fitnesses = new ArrayList<>(numFitnessFunctions);
		if(Parameters.parameters.booleanParameter("marioProgressPlusJumpsFitness")) {
			if(percentLevelPassed < 1.0) {
				fitnesses.add(percentLevelPassed);
			} else { // Level beaten
				fitnesses.add(1.0+jumps);
			}
		} 
		if(Parameters.parameters.booleanParameter("marioProgressPlusTimeFitness")) {
			if(percentLevelPassed < 1.0) {
				fitnesses.add(percentLevelPassed);
			} else { // Level beaten
				fitnesses.add(1.0+time);
			}
		}
		if(Parameters.parameters.booleanParameter("marioLevelMatchFitness")) {
			int diffCount = 0;
			
			if(oneLevel.size() != targetLevel.size()) {
				System.out.println("Target");
				System.out.println(targetLevel);
				System.out.println("Evolved");
				System.out.println(oneLevel);
				throw new IllegalStateException("Target level and evolved level are not even the same height.");
			}
						
			// This will hold the target level, except that every location of conflict with the evolved level will
			// be replaced with the blank passable background tile
			ArrayList<List<Integer>> targetDiff = new ArrayList<>();
			
			// TODO
			// Should this calculation include or eliminate the starting and ending regions we add to Mario levels?
			Iterator<List<Integer>> evolveIterator = oneLevel.iterator();
			Iterator<List<Integer>> targetIterator = targetLevel.iterator();
			while(evolveIterator.hasNext() && targetIterator.hasNext()) {
				Iterator<Integer> evolveRow = evolveIterator.next().iterator();
				Iterator<Integer> targetRow = targetIterator.next().iterator();
				List<Integer> diffRow = new ArrayList<>(targetLevel.get(0).size()); // For visualizing differences
				while(evolveRow.hasNext() && targetRow.hasNext()) {
					Integer nextInTarget = targetRow.next();
					if(!evolveRow.next().equals(nextInTarget)) {
						diffCount++;
						diffRow.add(-100); // An illegal tile. Indicates a conflict
					} else {
						diffRow.add(nextInTarget);
					}
				}
				targetDiff.add(diffRow);
			}
			// More differences = worse fitness
			fitnesses.add(-1.0*diffCount);
			
			if(CommonConstants.watch) {
				// View whole dungeon layout
				Level level = Parameters.parameters.booleanParameter("marioGANUsesOriginalEncoding") ? OldLevelParser.createLevelJson(targetDiff) : LevelParser.createLevelJson(targetDiff);			
				BufferedImage image = MarioLevelUtil.getLevelImage(level);
				String saveDir = FileUtilities.getSaveDirectory();
				int currentGen = ((GenerationalEA) MMNEAT.ea).currentGeneration();
				GraphicsUtil.saveImage(image, saveDir + File.separator + (currentGen == 0 ? "initial" : "gen"+ currentGen) + File.separator + "MarioLevel"+individual.getId()+"TargetDiff.png");
			}
		}
		
		
		// Encourages an alternating periodic pattern of Vanessa's objectives
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingLeniency")) {
			fitnesses.add(alternatingStatScore(levelStats, LENIENCY_STAT_INDEX));
		}
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingNegativeSpace")) {
			fitnesses.add(alternatingStatScore(levelStats, NEGATIVE_SPACE_STAT_INDEX));
		}
		if(Parameters.parameters.booleanParameter("marioLevelAlternatingDecoration")) {
			fitnesses.add(alternatingStatScore(levelStats, DECORATION_FREQUENCY_STAT_INDEX));
		}

		// Encourages a symmetric pattern of Vanessa's objectives
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricLeniency")) {
			fitnesses.add(symmetricStatScore(levelStats, LENIENCY_STAT_INDEX));
		}
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricNegativeSpace")) {
			fitnesses.add(symmetricStatScore(levelStats, NEGATIVE_SPACE_STAT_INDEX));
		}
		if(Parameters.parameters.booleanParameter("marioLevelSymmetricDecoration")) {
			fitnesses.add(symmetricStatScore(levelStats, DECORATION_FREQUENCY_STAT_INDEX));
		}
		
		if(Parameters.parameters.booleanParameter("marioRandomFitness")) {
			fitnesses.add(RandomNumbers.fullSmallRand());
		}
		
		return new Pair<double[],double[]>(ArrayUtil.doubleArrayFromList(fitnesses), otherScores);
	}

	private double symmetricStatScore(ArrayList<double[]> levelStats, int statIndex) {
		double total = 0;
		for(int i = 0; i < levelStats.size()/2; i++) {
			// Diff between symmetric segments
			total += Math.abs(levelStats.get(i)[statIndex] - levelStats.get(levelStats.size()-1-i)[statIndex]);
		}
		return - total; // Negative: Max symmetry means minimal difference in symmetric segments
	}

	private double alternatingStatScore(ArrayList<double[]> levelStats, int statIndex) {
		double total = 0;
		for(int i = 1; i < levelStats.size(); i++) {
			// Differences between adjacent segments
			total += Math.abs(levelStats.get(i-1)[statIndex] - levelStats.get(i)[statIndex]);
		}
		return total;
	}
	
}
