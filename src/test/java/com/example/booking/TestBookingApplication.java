package com.example.booking;

import org.springframework.boot.SpringApplication;

public class TestBookingApplication {

	public static void main(String[] args) {
		SpringApplication.from(BookingApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
