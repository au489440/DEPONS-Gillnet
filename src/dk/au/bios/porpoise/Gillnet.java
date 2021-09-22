package dk.au.bios.porpoise;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import dk.au.bios.porpoise.util.DebugLog;
import dk.au.bios.porpoise.util.SimulationTime;
import repast.simphony.context.Context;
import repast.simphony.query.space.continuous.ContinuousWithin;
import repast.simphony.random.RandomHelper;
import repast.simphony.space.SpatialException;
import repast.simphony.space.continuous.ContinuousSpace;
import repast.simphony.space.continuous.NdPoint;
import repast.simphony.space.grid.Grid;
import repast.simphony.space.grid.GridPoint;

/**
 * A Gillnet has a impact which deters the porpoise away from the area, and a by-catch probability for any porpoise
 * which enters the cell of the net.
 */
public class Gillnet extends Agent {

	private static LinkedList<Gillnet> GILLNET_CREATE_QUEUE = new LinkedList<>();

	private String name;
	private double locX;
	private double locY;
	private double impact;
	private int netStartTick;
	private int netEndTick;
	private double bycatchProbability;

	protected Gillnet(ContinuousSpace<Agent> space, Grid<Agent> grid, String name, double impact, double locX,
			double locY, int netStartTick, int netEndTick, double bycatchProbability, int id) {
		super(space, grid, id);
		this.name = name;
		this.impact = impact;
		this.locX = locX;
		this.locY = locY;
		this.netStartTick = netStartTick;
		this.netEndTick = netEndTick;
		this.bycatchProbability = bycatchProbability;
	}

	/**
	 * Load turbines from a data file. The corresponding methods in Netlogo are turbs-import-pos and turbs-setup.
	 * 
	 * @param fileName The name of the turbines file to load (excluding path and extension).
	 * @return List of turbines read from the file.
	 * @throws Exception Error reading the file.
	 */
	public static void load(Context<Agent> context, ContinuousSpace<Agent> space, Grid<Agent> grid, String fileName)
			throws Exception {
		GILLNET_CREATE_QUEUE.clear();

		File file = new File("data/gillnets", fileName + ".txt");

		int numGillnets = 0;

		try (BufferedReader fr = new BufferedReader(new FileReader(file))) {
			fr.readLine(); // Header is ignored.
			String line;
			while ((line = fr.readLine()) != null) {
				if (line.trim().length() < 1 || line.startsWith("#")) {
					continue;
				}
				String[] cols = line.split("\\s+");
				String name = cols[0];
				double locX = (Double.parseDouble(cols[1]) - Globals.getXllCorner()) / 400;
				double locY = (Double.parseDouble(cols[2]) - Globals.getYllCorner()) / 400;
				double impact = Double.parseDouble(cols[3]);
				int netStartTick = -1;
				int netEndTick = -1;
				double bycatchProbability = 1.0;
				if (cols.length >= 7) {
					netStartTick = Integer.parseInt(cols[4]);
					netEndTick = Integer.parseInt(cols[5]);
					bycatchProbability = Double.parseDouble(cols[6]);
				}

				Gillnet t = new Gillnet(space, grid, name, impact, locX, locY, netStartTick, netEndTick,
						bycatchProbability, numGillnets);
				GILLNET_CREATE_QUEUE.add(t);
			}
		}

		Collections.sort(GILLNET_CREATE_QUEUE, new Comparator<Gillnet>() {
			@Override
			public int compare(Gillnet o1, Gillnet o2) {
				if (o1.netStartTick > o2.netStartTick) {
					return 1;
				} else if (o1.netStartTick == o2.netStartTick) {
					return 0;
				} else {
					return -1;
				}
			}
		});

		System.out.println("Loaded " + GILLNET_CREATE_QUEUE.size() + " gillnets");
	}

	/**
	 * Add and remove Gillnet agents based on startTick and endTick.
	 *
	 * @param context
	 * @param space
	 * @param grid
	 */
	public static void lifecycle(Context<Agent> context) {
		deactiveExpired(context);
		activateStarted(context);
	}

	private static void activateStarted(Context<Agent> context) {
		double now = SimulationTime.getTick();
		// Add new Gillnets with startTick reached
		do {
			if (GILLNET_CREATE_QUEUE.isEmpty()) {
				break;
			}
			Gillnet first = GILLNET_CREATE_QUEUE.getFirst();
			if (first != null && first.getNetStartTick() <= now) {
				context.add(first);
				try {
					first.initialize();
				} catch (SpatialException e) {
					context.remove(first);
					System.err.println("Did not add gillnet " + first.name + " due to an error." + e.getMessage());
				}
				GILLNET_CREATE_QUEUE.removeFirst();
			} else {
				break;
			}
		} while (true);
	}

	private static void deactiveExpired(final Context<Agent> context) {
		double now = SimulationTime.getTick();
		List<Gillnet> gillnetToRemove = new LinkedList<>();
		for (Agent a : context.getObjects(Gillnet.class)) {
			Gillnet g = (Gillnet) a;
			if (g.getNetEndTick() < now) {
				gillnetToRemove.add(g);
			}
		}
		for (Gillnet g : gillnetToRemove) {
			context.remove(g);
		}

	}

	private void initialize() {
		this.setPosition(new NdPoint(locX, locY));
	}

	/**
	 * Deterrence by gillnet pinger
	 */
	public void deterPorpoise() {
		int simTick = (int) SimulationTime.getTick();
		if (simTick >= netStartTick && simTick <= netEndTick) {
			GridPoint location = getGrid().getLocation(this);
			Iterable<Agent> objectsAt = getGrid().getObjectsAt(location.getX(), location.getY());
			List<Porpoise> caughtPorpoises = new ArrayList<>();
			// The porpoises must "die" (removes them from the context) after the iterator has been completed.
			for (Agent a : objectsAt) {
				if (a instanceof Porpoise) {
					if (RandomHelper.nextDouble() < bycatchProbability) {
						caughtPorpoises.add((Porpoise) a);
					}
				}
			}
			for (Porpoise p : caughtPorpoises) {
				p.die(CauseOfDeath.ByCatchGillnet);
			}

			final double radius = Math.pow(10, ((impact - SimulationParameters.getDeterResponseThreshold()) / 20));
			final ContinuousWithin<Agent> affectedSpace = new ContinuousWithin<Agent>(this.getSpace(), this, radius);
			final Iterable<Agent> agents = affectedSpace.query();
			for (final Agent a : agents) {
				if (a instanceof Porpoise) {
					final Porpoise p = (Porpoise) a;
					final double distToTurb = this.getSpace().getDistance(getPosition(), p.getPosition()) * 400;
					if (distToTurb <= SimulationParameters.getDeterMaxDistance()) {
						// current amount of deterring the received-level (RL) gives the amount of noise that the 
						// porpoise is exposed to at a given distance, assuming cylindrical sound spreading;
						// RL = SL � 20Log10(dist)
						final double currentDeterence = impact
								- (SimulationParameters.getBetaHat() * Math.log10(distToTurb)
										+ (SimulationParameters.getAlphaHat() * distToTurb))
								- SimulationParameters.getDeterResponseThreshold();

						if (currentDeterence > 0) {
							p.deter(currentDeterence, this.getPosition());
						}

						if (DebugLog.isEnabledFor(8)) {
							DebugLog.print8("(porp {}) dist-to-gillnet {}: {} m, curr.deter: {}", p.getId(), this.name,
									Math.round(distToTurb), Math.round(currentDeterence));
						}
					}
				}
			}
		}
	}

	public String getName() {
		return name;
	}

	public int getNetStartTick() {
		return netStartTick;
	}

	public int getNetEndTick() {
		return netEndTick;
	}

}
