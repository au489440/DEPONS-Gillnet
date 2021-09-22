/*
 * Copyright (C) 2017-2019 Jacob Nabe-Nielsen <jnn@bios.au.dk>
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License version 2 and only version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, see 
 * <https://www.gnu.org/licenses>.
 * 
 * Linking DEPONS statically or dynamically with other modules is making a combined work based on DEPONS. 
 * Thus, the terms and conditions of the GNU General Public License cover the whole combination.
 * 
 * In addition, as a special exception, the copyright holders of DEPONS give you permission to combine DEPONS 
 * with free software programs or libraries that are released under the GNU LGPL and with code included in the 
 * standard release of Repast Simphony under the Repast Suite License (or modified versions of such code, with unchanged license). 
 * You may copy and distribute such a system following the terms of the GNU GPL for DEPONS and the licenses of the 
 * other code concerned.
 * 
 * Note that people who make modified versions of DEPONS are not obligated to grant this special exception for 
 * their modified versions; it is their choice whether to do so. 
 * The GNU General Public License gives permission to release a modified version without this exception; 
 * this exception also makes it possible to release a modified version which carries forward this exception.
 */

package dk.au.bios.porpoise;

import java.io.IOException;

import org.apache.log4j.Level;

import dk.au.bios.porpoise.agents.misc.TrackingDisplayAgent;
import dk.au.bios.porpoise.behavior.FastRefMemTurn;
import dk.au.bios.porpoise.behavior.GeneratedRandomSource;
import dk.au.bios.porpoise.behavior.RefMem;
import dk.au.bios.porpoise.behavior.RefMemTurnCalculator;
import dk.au.bios.porpoise.behavior.ReplayedRandomSource;
import dk.au.bios.porpoise.landscape.CellData;
import dk.au.bios.porpoise.landscape.LandscapeLoader;
import dk.au.bios.porpoise.ships.ShipLoader;
import dk.au.bios.porpoise.tasks.AddTrackedPorpoisesTask;
import dk.au.bios.porpoise.tasks.CaptureTestDataTask;
import dk.au.bios.porpoise.tasks.DailyTask;
import dk.au.bios.porpoise.tasks.DeadPorpoisesReportProxyCleanupTask;
import dk.au.bios.porpoise.tasks.DeterenceTask;
import dk.au.bios.porpoise.tasks.FoodTask;
import dk.au.bios.porpoise.tasks.MonthlyTasks;
import dk.au.bios.porpoise.tasks.YearlyTask;
import dk.au.bios.porpoise.util.DebugLog;
import dk.au.bios.porpoise.util.PorpoiseLifecycleLog;
import dk.au.bios.porpoise.util.test.PorpoiseTestDataCapturer;
import repast.simphony.context.Context;
import repast.simphony.context.space.continuous.ContinuousSpaceFactory;
import repast.simphony.context.space.continuous.ContinuousSpaceFactoryFinder;
import repast.simphony.context.space.grid.GridFactory;
import repast.simphony.context.space.grid.GridFactoryFinder;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.IAction;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.parameter.Parameters;
import repast.simphony.space.continuous.BouncyBorders;
import repast.simphony.space.continuous.ContinuousSpace;
import repast.simphony.space.continuous.NdPoint;
import repast.simphony.space.continuous.PointTranslator;
import repast.simphony.space.continuous.RandomCartesianAdder;
import repast.simphony.space.grid.Grid;
import repast.simphony.space.grid.GridBuilderParameters;
import repast.simphony.space.grid.SimpleGridAdder;
import repast.simphony.space.grid.WrapAroundBorders;
import repast.simphony.ui.RSApplication;
import repast.simphony.valueLayer.GridValueLayer;
import simphony.util.messages.MessageEvent;

/**
 * Constructs the Context for the simulation. This includes setting up the space
 * and grid, loading the environment and creating the various agents.
 */
public class PorpoiseSimBuilder implements ContextBuilder<Agent> {

	@Override
	public Context<Agent> build(final Context<Agent> context) {
		context.setId("PorpoiseSim");

		final Parameters params = RunEnvironment.getInstance().getParameters();
		SimulationParameters.initialize(params);

		Globals.setSimYears(params.getInteger("simYears"));
		if (Globals.getSimYears() == null && RunEnvironment.getInstance().isBatch()) {
			// If batch and parameter simYears is missing, then default to 30 years
			Globals.setSimYears(30);			
		}
		if (Globals.getSimYears() != null) {
			final int numSimSteps = (Globals.getSimYears() * 360 * 48) - 1;
			RunEnvironment.getInstance().endAt(numSimSteps);
		} 

		PorpoiseTestDataCapturer.capture(params);

		PorpoiseLifecycleLog.init();

		// Currently disabled - PSMVerificationLog.setup();
		// Disabled, enable to capture replay output
		// ReplayHelper.setup();

		if (Globals.getRandomReplaySource() != null) {
			Globals.setRandomSource(new ReplayedRandomSource(Globals.getRandomReplaySource()));
		} else {
			Globals.setRandomSource(new GeneratedRandomSource(params));
		}

		Globals.resetMonthlyStats();

		DebugLog.initialize(params);
		// Reset the counter for the porpoise id generator.
		Porpoise.PORPOISE_ID.set(0);

		Globals.setCellData(null); // This releases the previous CellData allowing it to be garbage collected
		final String landscape;
		if (SimulationParameters.isHomogenous()) {
			landscape = SimulationParameters.LANDSCAPE_HOMOGENOUS_NAME;
		} else {
			landscape = SimulationParameters.getLandscape();
		}
		final CellData cellData;
		try {
			final LandscapeLoader dataLoader = new LandscapeLoader(landscape);
			cellData = dataLoader.load();
			Globals.setCellData(cellData);
		} catch (IOException e) {
			if (RSApplication.getRSApplicationInstance() != null) {
				RSApplication.getRSApplicationInstance().getErrorLog().addError(new MessageEvent(this, Level.FATAL, "Error loading landscape data"));
				RSApplication.getRSApplicationInstance().getErrorLog().show();
			}
			throw new RuntimeException(e);
		}

		RefMem.initMemLists(params.getDouble("rS"), params.getDouble("rR"));

		final ContinuousSpace<Agent> space = buildSpace(context);
		final Grid<Agent> grid = buildGrid(context);

		final FoodAgentProxy foodAgent = new FoodAgentProxy(space, grid, 0);
		context.add(foodAgent);
		context.add(Globals.getMonthlyStats());

		addPorpoises(context, space, grid, cellData);

		addTrackedPorpoises(context, space, grid);

		if (SimulationParameters.isShipsEnabled()) {
			ShipLoader loader = new ShipLoader();
			try {
				loader.load(context, space, grid, landscape);
			} catch (Exception e) {
				if (RSApplication.getRSApplicationInstance() != null) {
					RSApplication.getRSApplicationInstance().getErrorLog().addError(new MessageEvent(this, Level.FATAL, "Error loading ship data"));
					RSApplication.getRSApplicationInstance().getErrorLog().show();
				}
				throw new RuntimeException(e);
			}
		}

		addTurbines(context, space, grid);
		addGillnets(context, space, grid);

		for (final Agent a : context) {
			final NdPoint pt = space.getLocation(a);
			grid.moveTo(a, (int) pt.getX(), (int) pt.getY());
		}

		setupSchedules(context, space, grid, cellData);
		addVisualAgents(context, space, grid, cellData);
		addBlocks(cellData.getBlock(), space, grid, context);

		/*
		 * Parameter removed, should be hardcoded to false if
		 * (params.getBoolean("showFoodPatch")) { addFoodPatches(cellData, context,
		 * space, grid); }
		 */

		return context;
	}

	private ContinuousSpace<Agent> buildSpace(final Context<Agent> context) {
		final boolean wrapBorder = SimulationParameters.isHomogenous() && SimulationParameters.isWrapBorderHomo();
		PointTranslator pointTranslator;
		if (wrapBorder) {
			pointTranslator = new repast.simphony.space.continuous.WrapAroundBorders();
		} else {
			pointTranslator = new BouncyBorders();
		}
		final ContinuousSpaceFactory factory = ContinuousSpaceFactoryFinder.createContinuousSpaceFactory(null);
		final ContinuousSpace<Agent> space = factory.createContinuousSpace("space", context,
				new RandomCartesianAdder<Agent>(), pointTranslator,
				new double[] { Globals.getWorldWidth(), Globals.getWorldHeight() }, new double[] { 0.5f, 0.5f });

		return space;
	}

	private Grid<Agent> buildGrid(final Context<Agent> context) {
		final GridFactory gridFactory = GridFactoryFinder.createGridFactory(null);
		final Grid<Agent> grid = gridFactory.createGrid("grid", context,
				new GridBuilderParameters<Agent>(new repast.simphony.space.grid.BouncyBorders(),
						new SimpleGridAdder<Agent>(), true, Globals.getWorldWidth(), Globals.getWorldHeight()));
		// Grid<Agent> grid = gridFactory.createGrid("grid", context, new
		// GridBuilderParameters<Agent>(new
		// repast.simphony.space.grid.WrapAroundBorders(), new SimpleGridAdder<Agent>(),
		// true, Globals.WORLD_WIDTH,
		// Globals.WORLD_HEIGHT));

		return grid;
	}

	private void addPorpoises(final Context<Agent> context, final ContinuousSpace<Agent> space, final Grid<Agent> grid,
			final CellData cellData) {
		// Fast ref mem is the one that has been validated;
		final RefMemTurnCalculator refMemTurn = new FastRefMemTurn(); // : new OriginalRefMemTurn();

		final int[] ageDistribution = new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
				1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
				1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
				2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
				3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
				5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9, 9, 9,
				9, 9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 13, 13,
				13, 13, 14, 14, 14, 14, 15, 15, 15, 15, 18, 18, 19, 19, 21, 22 };

		final int numPorpoises = SimulationParameters.getPorpoiseCount();
		for (int i = 0; i < numPorpoises; i++) {
			final int nextAgeDistrib = Globals.getRandomSource().nextAgeDistrib(0, ageDistribution.length);
			final Porpoise p = new Porpoise(space, grid, context, ageDistribution[nextAgeDistrib], refMemTurn);
			context.add(p);
			p.moveAwayFromLand();

			final NdPoint initialPoint = Globals.getRandomSource().getInitialPoint();
			final Double initialHeading = Globals.getRandomSource().getInitialHeading();

			if (initialPoint != null) {
				p.setPosition(initialPoint);
				p.reinitializePoslist();
				p.setHeading(initialHeading);
			}
			PorpoiseTestDataCapturer.capture(p);
		}
	}

	private void addTrackedPorpoises(final Context<Agent> context, final ContinuousSpace<Agent> space,
			final Grid<Agent> grid) {
		final int trackedPorpoisesCount = SimulationParameters.getTrackedPorpoiseCount();
		if (trackedPorpoisesCount > 0) {
			final AddTrackedPorpoisesTask addTrackedPorpoisesTask = new AddTrackedPorpoisesTask(context, space, grid,
					SimulationParameters.getLandscape(), trackedPorpoisesCount);
			addTrackedPorpoisesTask.setup();
		}
	}

	private void addTurbines(final Context<Agent> context, final ContinuousSpace<Agent> space, final Grid<Agent> grid) {
		final String turbines = SimulationParameters.getTurbines();
		if (turbines != null && !"off".equalsIgnoreCase(turbines)) {
			// Load and set up turbines
			try {
				Turbine.load(context, space, grid, turbines, RunEnvironment.getInstance().isBatch());
			} catch (final Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private void addGillnets(final Context<Agent> context, final ContinuousSpace<Agent> space, final Grid<Agent> grid) {
		String gillnets = SimulationParameters.getGillnets();
		if (gillnets != null && !"off".equalsIgnoreCase(gillnets)) {
			try {
				Gillnet.load(context, space, grid, gillnets);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Add helper agents to display visual. Only added in the RunEnvironment is not
	 * batch.
	 */
	private void addVisualAgents(final Context<Agent> context, final ContinuousSpace<Agent> space,
			final Grid<Agent> grid, final CellData cellData) {
		if (!RunEnvironment.getInstance().isBatch()) {
			final GridValueLayer visitedCellValueLayer = new GridValueLayer("visitedCell", 0.000f, true,
					new WrapAroundBorders(), Globals.getWorldWidth(), Globals.getWorldHeight());
			context.addValueLayer(visitedCellValueLayer);

			final BackgroundAgent ba = new BackgroundAgent(space, grid);
			context.add(ba);
			ba.initialize();

			final TrackingDisplayAgent tda = new TrackingDisplayAgent(space, grid, 0);
			context.add(tda);
			tda.initialize();

			// CellDataDistToCoastAgent cdd2ca = new CellDataDistToCoastAgent(space, grid,
			// cellData);
			// context.add(cdd2ca);
			// cdd2ca.initialize();
		}

	}

	private void setupSchedules(final Context<Agent> context, final ContinuousSpace<Agent> space,
			final Grid<Agent> grid, final CellData cellData) {
		final ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();

		final ScheduleParameters foodParams = ScheduleParameters.createRepeating(48, 48, AgentPriority.FOOD);

		final IAction deadPorpoisesTask = new DeadPorpoisesReportProxyCleanupTask(context);
		final ScheduleParameters deadPorpoisesParams = ScheduleParameters.createRepeating(0, 1,
				AgentPriority.FIRST_EVERY_TICK);
		schedule.schedule(deadPorpoisesParams, deadPorpoisesTask);

		// Special tick#1 daily-tasks
		final ScheduleParameters dailyParamsDay1 = ScheduleParameters.createOneTime(1, AgentPriority.DAILY);
		final ScheduleParameters dailyParams = ScheduleParameters.createRepeating(48, 24 * 2, AgentPriority.DAILY);

		final ScheduleParameters monthlyParamsDay1 = ScheduleParameters.createOneTime(1, AgentPriority.MONTHLY);
		final ScheduleParameters monthlyParams = ScheduleParameters.createRepeating(30 * 24 * 2, 30 * 24 * 2,
				AgentPriority.MONTHLY);

		final ScheduleParameters yearlyParamsDay1 = ScheduleParameters.createOneTime(1, AgentPriority.YEARLY);
		final ScheduleParameters yearlyParams = ScheduleParameters.createRepeating(360 * 24 * 2, 360 * 24 * 2,
				AgentPriority.YEARLY);

		final IAction dailyTask = new DailyTask(context);
		final IAction monthlyTask = new MonthlyTasks();
		final IAction yearlyTask = new YearlyTask(context);

		schedule.schedule(dailyParamsDay1, dailyTask);
		schedule.schedule(dailyParams, dailyTask);

		schedule.schedule(monthlyParamsDay1, monthlyTask);
		schedule.schedule(monthlyParams, monthlyTask);

		schedule.schedule(yearlyParamsDay1, yearlyTask);
		schedule.schedule(yearlyParams, yearlyTask);

		schedule.schedule(foodParams, new FoodTask());

		if (PorpoiseTestDataCapturer.capture) {
			schedule.schedule(ScheduleParameters.createRepeating(0, 1, ScheduleParameters.LAST_PRIORITY),
					new CaptureTestDataTask(context));
		}

		if (SimulationParameters.getModel() >= 3) {
			final ScheduleParameters deterenceParams = ScheduleParameters.createRepeating(0, 1,
					AgentPriority.PORP_DETERRENCE);
			schedule.schedule(deterenceParams, new DeterenceTask(context));
		}
	}

	/**
	 * Adds the required block dummy agents to the model. They are only there to
	 * enable the text sinks to use them for dumping the numnber of porpoises in
	 * them.
	 */
	private void addBlocks(final int[][] blocks, final ContinuousSpace<Agent> space, final Grid<Agent> grid,
			final Context<Agent> context) {
		int maxBlock = -1;

		for (int x = 0; x < blocks.length; x++) {
			for (int y = 0; y < blocks[x].length; y++) {
				if (blocks[x][y] > maxBlock) {
					maxBlock = blocks[x][y];
				}
			}
		}

		Block.initialize(maxBlock + 1);

		for (int i = 0; i < (maxBlock + 1); i++) {
			final Block b = new Block(space, grid, i, context);
			context.add(b);
		}
	}

	/*
	 * private void queueTask(String intervalText, ISchedule schedule, IAction
	 * action) { if (!intervalText.equals("off")) { int interval; if
	 * (intervalText.equals("step")) { interval = 1; } else if
	 * (intervalText.equals("daily")) { interval = 24 * 2; } else if
	 * (intervalText.equals("monthly")) { interval = 24 * 2 * 30; } else if
	 * (intervalText.equals("yearly")) { interval = 24 * 2 * 30 * 360; } else if
	 * (intervalText.equals("one-porp")) { throw new
	 * java.lang.NoSuchMethodError("Not implemented"); } else { throw new
	 * RuntimeException("Unknown interval : " + intervalText); }
	 * 
	 * ScheduleParameters scheduleParam = ScheduleParameters.createRepeating(0,
	 * interval, ScheduleParameters.LAST_PRIORITY); schedule.schedule(scheduleParam,
	 * action); } }
	 * 
	 * // Utility to convert bathy file in case depth is negative. Uses -9999 as no
	 * value. private void convertBathy(String f) { try { BufferedWriter out = new
	 * BufferedWriter(new FileWriter("c:/temp/out.txt"));
	 * 
	 * FileReader freader = new FileReader(f); BufferedReader reader = new
	 * BufferedReader(freader);
	 * 
	 * for (int i = 0; i < 6; i++) { out.write(reader.readLine()); out.write('\n');
	 * }
	 * 
	 * int y = 0; String line; while ((line = reader.readLine()) != null) { String[]
	 * points = line.split(" "); for (int x = 0; x < points.length; x++) { int i =
	 * Integer.parseInt(points[x]);
	 * 
	 * if (i == -9999) { out.write(Integer.toString(i)); } else {
	 * out.write(Integer.toString(i * -1)); }
	 * 
	 * if (x + 1 < points.length) { out.write(' '); } else { out.write('\n'); } }
	 * y++; }
	 * 
	 * reader.close(); freader.close(); out.close(); } catch (Exception e) {
	 * e.printStackTrace(); } }
	 * 
	 * private void createFile(String file, int columns, int rows, String s) { try {
	 * BufferedWriter out = new BufferedWriter(new FileWriter(file));
	 * 
	 * for (int row = 0; row < rows; row++) { for (int column = 0; column < columns;
	 * column++) { out.write(s); out.write(' '); } out.write('\n'); }
	 * 
	 * out.close(); } catch (Exception e) { e.printStackTrace(); } }
	 * 
	 * private void createFoodProbFile(String file, int columns, int rows, double
	 * prob) { try { BufferedWriter out = new BufferedWriter(new FileWriter(file));
	 * 
	 * for (int row = 0; row < rows; row++) { for (int column = 0; column < columns;
	 * column++) { if (((int) Math.floor(RandomHelper.nextDoubleFromTo(0, prob))) ==
	 * 1) { // Using RandomHelper here out.write('1'); } else { out.write('0'); }
	 * 
	 * out.write(' '); } out.write('\n'); }
	 * 
	 * out.close(); } catch (Exception e) { e.printStackTrace(); } }
	 * 
	 * private void addFoodPatches(CellData data, Context<Agent> context,
	 * ContinuousSpace<Agent> space, Grid<Agent> grid) { Pair[] patches =
	 * data.getFoodProbAboveZeroPatches();
	 * 
	 * for (Pair p : patches) { FoodPatch fp = new FoodPatch(space, grid, new
	 * GridPoint(p.first, p.second), data); context.add(fp); fp.setPosition(new
	 * NdPoint(p.first, p.second));
	 * 
	 * System.out.println("Adding <" + p.first + "," + p.second + ">");
	 * 
	 * } }
	 */

}
