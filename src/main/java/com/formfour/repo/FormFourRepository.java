package com.formfour.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.formfour.model.OwnershipDocument;

public interface FormFourRepository extends MongoRepository<OwnershipDocument, String> {

    @Query("{'nonDerivativeTable.nonDerivativeTransaction.transactionValue': {$gte: ?0} }")
    Page<OwnershipDocument> findWithValueGreaterThan(BigDecimal value, Pageable page);

    @Query("{'filingEntity': ?0, 'nonDerivativeTable.nonDerivativeTransaction.transactionValue': {$gte: ?1} }")
    Page<OwnershipDocument> findByFilingEntityWithValueGreaterThan(String filingEntity, BigDecimal value, Pageable page);

    List<OwnershipDocument> findAllByFilingEntity(String filingEntity, Pageable page);

    Page<OwnershipDocument> findByAnomalyScoreGreaterThanEqualOrderByAnomalyScoreDesc(
            double minScore, Pageable page);

    long countByAnomalyScoreGreaterThan(double value);
}
