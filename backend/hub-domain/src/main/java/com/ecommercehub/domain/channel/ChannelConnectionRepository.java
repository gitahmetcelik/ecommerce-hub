package com.ecommercehub.domain.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChannelConnectionRepository extends JpaRepository<ChannelConnection, UUID> {
}
