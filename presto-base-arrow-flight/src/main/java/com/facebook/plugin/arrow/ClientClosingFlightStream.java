package com.facebook.plugin.arrow;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStream;

import static java.util.Objects.requireNonNull;

public class ClientClosingFlightStream
        implements AutoCloseable
{
    private final FlightStream flightStream;
    private final FlightClient flightClient;

    public ClientClosingFlightStream(FlightStream flightStream, FlightClient flightClient)
    {
        this.flightStream = requireNonNull(flightStream, "flightStream is null");
        this.flightClient = requireNonNull(flightClient, "flightClient is null");
    }

    public FlightStream getFlightStream()
    {
        return flightStream;
    }

    @Override
    public void close()
            throws Exception
    {
        try {
            flightStream.close();
        }
        finally {
            flightClient.close();
        }
    }
}
