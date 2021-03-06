package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.run.RunDuesseldorfScenario;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

import static org.matsim.run.RunDuesseldorfScenario.VERSION;

/**
 * This script utilizes GTFS2MATSim and creates a pseudo network and vehicles using MATSim standard API functionality.
 *
 * @author rakow
 */
@CommandLine.Command(
        name = "transit",
        description = "Create transit schedule from GTFS data",
        showDefaultValues = true
)
public class CreateTransitSchedule implements Callable<Integer> {

    @CommandLine.Parameters(arity = "1..*", paramLabel = "INPUT", description = "Input GTFS zip files")
    private List<Path> gtfsFiles;

    @CommandLine.Option(names = "--network", description = "Base network that will be merged with pt network.", required = true)
    private Path networkFile;

    @CommandLine.Option(names = "--output", description = "Output folder", defaultValue = "scenarios/input")
    private File output;

    @CommandLine.Option(names = "--input-cs", description = "Input coordinate system of the data", defaultValue = TransformationFactory.WGS84)
    private String inputCS;

    @CommandLine.Option(names = "--target-cs", description = "Target coordinate system of the network", defaultValue = RunDuesseldorfScenario.COORDINATE_SYSTEM)
    private String targetCS;

    @CommandLine.Option(names = "--date", description = "The day for which the schedules will be extracted", defaultValue = "2020-06-08")
    private LocalDate date;

    public static void main(String[] args) {
        System.exit(new CommandLine(new CreateTransitSchedule()).execute(args));
    }

    @Override
    public Integer call() throws Exception {

        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(inputCS, targetCS);

        // Output files
        File scheduleFile = new File(output, "duesseldorf-" + VERSION + "-transitSchedule.xml.gz");
        File networkPTFile = new File(output, networkFile.getFileName().toString().replace(".xml", "-with-pt.xml"));
        File transitVehiclesFile = new File(output, "duesseldorf-" + VERSION + "-transitVehicles.xml.gz");

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

        for (Path gtfsFile : gtfsFiles) {

            // TODO: possibly some SPNV could be duplicated

            GtfsConverter converter = GtfsConverter.newBuilder()
                    .setScenario(scenario)
                    .setTransform(ct)
                    .setDate(date)
                    .setFeed(gtfsFile)
                    //.setIncludeAgency(agency -> agency.equals("rbg-70"))
                    .setIncludeStop(stop -> {
                        Coord coord = ct.transform(new Coord(stop.stop_lon, stop.stop_lat));
                        return coord.getX() >= RunDuesseldorfScenario.X_EXTENT[0] && coord.getX() <= RunDuesseldorfScenario.X_EXTENT[1] &&
                                coord.getY() >= RunDuesseldorfScenario.Y_EXTENT[0] && coord.getY() <= RunDuesseldorfScenario.Y_EXTENT[1];
                    })
                    .setMergeStops(true)
                    .build();

            converter.convert();
        }

        Network network = Files.exists(networkFile) ? NetworkUtils.readNetwork(networkFile.toString()) : scenario.getNetwork();

        // Create a network around the schedule
        new CreatePseudoNetwork(scenario.getTransitSchedule(), network, "pt_").createNetwork();
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();

        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(scheduleFile.getAbsolutePath());
        new NetworkWriter(network).write(networkPTFile.getAbsolutePath());
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(transitVehiclesFile.getAbsolutePath());

        return 0;
    }

}
