package com.formfour.repo;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.formfour.model.TickerBaseline;

public interface TickerBaselineRepository extends MongoRepository<TickerBaseline, String> {
}
