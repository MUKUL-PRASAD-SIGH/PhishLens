package com.phishlens.scanapi.web;

public record ErrorResponse(int status, String error, String message) {
}
