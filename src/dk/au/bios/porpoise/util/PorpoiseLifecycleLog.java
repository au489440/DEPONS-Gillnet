package dk.au.bios.porpoise.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import dk.au.bios.porpoise.CauseOfDeath;
import dk.au.bios.porpoise.Porpoise;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.environment.RunState;

public class PorpoiseLifecycleLog {

	private static PrintWriter porpoiseLifecycleOutput = null;

	public static void init() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MMM.dd.HH_mm_ss_SS");
		File porpoiseLifecycleOutputFile = new File("Porpoise_Lifecycle" + sdf.format(new Date()) + ".csv");
		try {
			porpoiseLifecycleOutput = new PrintWriter(porpoiseLifecycleOutputFile);
			if (RunEnvironment.getInstance().isBatch()) {
				porpoiseLifecycleOutput.printf("\"run\",");
			}
			porpoiseLifecycleOutput.printf("\"tick\",\"Porpoise\",\"Cause of death\",\"UtmX\";\"UtmY\",\"Age\"%n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void writeDeath(Porpoise porpoise, CauseOfDeath cause) {
		if (RunEnvironment.getInstance().isBatch()) {
			porpoiseLifecycleOutput.printf("%d,", RunState.getInstance().getRunInfo().getRunNumber());
		}
		porpoiseLifecycleOutput.printf("%.1f,%d,%s,%d,%d,%.3f%n", SimulationTime.getTick(), porpoise.getId(),
				cause.toString(), porpoise.getUtmX(), porpoise.getUtmY(), porpoise.getAge());
		porpoiseLifecycleOutput.flush();
	}

}
