package com.blankgrid.aione.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 部门名,如"产品部" */
    @Column(nullable = false)
    private String name;

    /** 部门主题色(低饱和,前端品牌色板键,如 product/tech/design/market/ops) */
    private String colorKey = "tech";

    /** 排序号 */
    private Integer sortOrder = 0;

    public Department() {}

    public Department(String name, String colorKey, int sortOrder) {
        this.name = name;
        this.colorKey = colorKey;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColorKey() { return colorKey; }
    public void setColorKey(String colorKey) { this.colorKey = colorKey; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
